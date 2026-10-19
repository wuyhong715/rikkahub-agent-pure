package me.rerere.rikkahub.data.mission

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.subagent.SubAgentRun
import me.rerere.rikkahub.subagent.SubAgentStatus
import me.rerere.rikkahub.runtime.util.JsonInstant
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MissionEngine"

/**
 * Tools whose execution produces a file artifact that should be tracked on the
 * bound mission's timeline. The [path] field is extracted from the tool's args
 * JSON and recorded via [MissionRepository.attachArtifact].
 */
private val ARTIFACT_TOOLS = setOf(
    "write_text_file",
    "workspace_write_file",
    "workspace_edit_file",
)

/**
 * Workbench Mission engine — bridges the conversation / sub-agent lifecycle to
 * [MissionRepository]'s timeline.
 *
 * Three responsibilities:
 * 1. **Conversation binding** — maps a conversationId to a missionId in memory
 *    (with DB fallback via [MissionRepository.findActiveByConversation]).
 * 2. **Tool execution journaling** — every tool execution emits a
 *    [RunEventType.TOOL_RESULT] event to the run-event journal; whitelisted
 *    file-writing tools additionally have their path attached as an artifact.
 * 3. **Sub-agent dispatch tracking** — collects [SubAgentRegistry.runs] and logs
 *    SUBAGENT_DISPATCHED / SUBAGENT_FINISHED events for runs whose parent chat
 *    has a bound mission.
 *
 * All public methods are wrapped in [runCatching] — this is observability
 * infrastructure and must never break the generation loop.
 *
 * @param repository the mission data layer.
 * @param runEventRepository the run-event journal writer (TOOL_RESULT emissions).
 * @param subAgentRegistry the in-memory sub-agent run store.
 * @param appScope the application-scoped coroutine scope for the init collector.
 */
class MissionEngine(
    private val repository: MissionRepository,
    private val runEventRepository: RunEventRepository,
    private val subAgentRegistry: SubAgentRegistry,
    private val appScope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** conversationId → missionId (in-memory binding cache). */
    private val bindings = ConcurrentHashMap<String, String>()

    /** "runId:status" pairs already processed, to prevent duplicate timeline events. */
    private val seenRunStatuses = ConcurrentHashMap.newKeySet<String>()

    init {
        @Suppress("unused")
        appScope.launch {
            subAgentRegistry.runs.collect { runsMap ->
                runsMap.values.forEach { run ->
                    runCatching { processRun(run) }
                        .onFailure { Log.w(TAG, "processRun failed for ${run.id}", it) }
                }
            }
        }
    }

    /**
     * Process a single [SubAgentRun] for timeline events. Only runs whose
     * [SubAgentRun.parentChatId] has a bound mission are tracked.
     *
     * - First [SubAgentStatus.RUNNING] → log SUBAGENT_DISPATCHED.
     * - First terminal status → log SUBAGENT_FINISHED (detail = result/error, first 200 chars).
     *
     * Deduplication via [seenRunStatuses] ensures each status transition is logged at most once.
     */
    private suspend fun processRun(run: SubAgentRun) {
        val parentChatId = run.parentChatId ?: return
        val missionId = missionIdFor(parentChatId) ?: return

        val key = "${run.id}:${run.status}"
        if (!seenRunStatuses.add(key)) return // already processed this run+status

        when (run.status) {
            SubAgentStatus.RUNNING -> {
                repository.logEvent(
                    missionId = missionId,
                    type = RunEventType.SUBAGENT_DISPATCHED,
                    title = run.label,
                    runId = run.id,
                    conversationId = parentChatId,
                )
            }

            SubAgentStatus.SUCCEEDED,
            SubAgentStatus.FAILED,
            SubAgentStatus.TIMED_OUT,
            SubAgentStatus.CANCELLED -> {
                val detail = run.result?.take(200) ?: run.error?.take(200)
                repository.logEvent(
                    missionId = missionId,
                    type = RunEventType.SUBAGENT_FINISHED,
                    title = run.label,
                    detail = detail,
                    runId = run.id,
                    conversationId = parentChatId,
                )
            }

            SubAgentStatus.PENDING -> { /* no event — wait for RUNNING */ }
        }
    }

    /**
     * Bind a conversation to a mission. Stores the binding in memory and logs a
     * NOTE event for audit. The [MissionEntity.conversationId] column is not
     * directly updatable through the current [MissionRepository] API — missions
     * created via [MissionRepository.create] with a conversationId are already
     * persisted; this method handles the runtime binding for lookups.
     */
    suspend fun bindConversation(conversationId: String, missionId: String) {
        runCatching {
            bindings[conversationId] = missionId
            repository.logEvent(
                missionId = missionId,
                type = RunEventType.NOTE,
                title = "Bound to conversation: $conversationId",
            )
        }.onFailure { Log.w(TAG, "bindConversation failed", it) }
    }

    /**
     * Remove the conversation→mission binding from memory. Logs a NOTE event
     * if a binding existed.
     */
    suspend fun unbindConversation(conversationId: String) {
        runCatching {
            val missionId = bindings.remove(conversationId)
            if (missionId != null) {
                repository.logEvent(
                    missionId = missionId,
                    type = RunEventType.NOTE,
                    title = "Unbound from conversation: $conversationId",
                )
            }
        }.onFailure { Log.w(TAG, "unbindConversation failed", it) }
    }

    /**
     * Resolve the missionId bound to [conversationId]. Checks the in-memory
     * [bindings] map first; on miss, falls back to
     * [MissionRepository.findActiveByConversation] and backfills the cache.
     */
    suspend fun missionIdFor(conversationId: String): String? = runCatching {
        bindings[conversationId] ?: repository.findActiveByConversation(conversationId)?.let { entity ->
            bindings[conversationId] = entity.id
            entity.id
        }
    }.getOrNull()

    /**
     * Hook called after a tool executes. Two responsibilities (in order):
     *
     * a) **Unconditionally emit a [RunEventType.TOOL_RESULT] event** to the run-event
     *    journal. This fires for every tool call in the generation loop, regardless of
     *    mission binding — [missionId] is nullable so unbound conversations still get
     *    journaled (scoped by [conversationId] only). title = tool name, detail = output
     *    excerpt (first 300 chars, null if blank), payload = {"tool", "args" excerpt}.
     *
     * b) **Artifact tracking** — if the tool is in [ARTIFACT_TOOLS], extracts the
     *    `path` field from [argsJson] and attaches it as an artifact to the bound
     *    mission via [MissionRepository.attachArtifact].
     */
    suspend fun onToolExecuted(
        conversationId: String,
        toolName: String,
        argsJson: String,
        outputJson: String,
    ) {
        runCatching {
            // (a) TOOL_RESULT — unconditional, one row per tool execution.
            runEventRepository.emit(
                type = RunEventType.TOOL_RESULT,
                title = toolName,
                detail = outputJson.take(300).ifBlank { null },
                payloadJson = JsonInstant.encodeToString(
                    buildJsonObject {
                        put("tool", toolName)
                        put("args", argsJson.take(500))
                    }
                ),
                missionId = missionIdFor(conversationId),
                conversationId = conversationId,
            )

            // (b) ARTIFACT_TOOLS whitelist — attach file artifacts to the bound mission,
            // with change metadata (diff summary) so the workbench can render a review
            // view without re-reading files (batch 1: delivery closure).
            if (toolName !in ARTIFACT_TOOLS) return@runCatching
            val path = parsePath(argsJson) ?: return@runCatching
            val missionId = missionIdFor(conversationId) ?: return@runCatching
            val change = computeArtifactChange(toolName, argsJson)
            repository.attachArtifact(missionId, path, toolName, change = change)
        }.onFailure { Log.w(TAG, "onToolExecuted failed for $toolName", it) }
    }

    /**
     * Hook called when a generation turn finishes. Logs a MILESTONE event on
     * the bound mission's timeline.
     */
    suspend fun onGenerationFinished(conversationId: String) {
        runCatching {
            val missionId = missionIdFor(conversationId) ?: return@runCatching
            repository.logEvent(
                missionId = missionId,
                type = RunEventType.MILESTONE,
                title = "Generation finished",
                conversationId = conversationId,
            )
        }.onFailure { Log.w(TAG, "onGenerationFinished failed", it) }
    }

    /**
     * Extract the `path` field from a tool's args JSON string.
     * Returns null if the JSON is invalid or has no `path` field.
     */
    private fun parsePath(argsJson: String): String? = runCatching {
        val element = json.parseToJsonElement(argsJson.ifBlank { "{}" })
        (element as? JsonObject)?.get("path")?.jsonPrimitive?.content
    }.getOrNull()

    /**
     * Compute an [ArtifactChange] for a file-writing tool from its args JSON.
     *
     * - `workspace_write_file` / `write_text_file`: changeType = "created",
     *   linesAdded = line count of the `text` arg.
     * - `workspace_edit_file`: changeType = "modified", linesAdded/linesRemoved
     *   derived from the `old_text` / `new_text` args (best-effort line delta;
     *   not a true diff — the workbench only needs a summary).
     *
     * Returns null if the args cannot be parsed or the tool is not a file writer.
     */
    private fun computeArtifactChange(toolName: String, argsJson: String): ArtifactChange? =
        runCatching {
            val element = json.parseToJsonElement(argsJson.ifBlank { "{}" }) as? JsonObject ?: return null
            when (toolName) {
                "workspace_write_file", "write_text_file" -> {
                    val text = element["text"]?.jsonPrimitive?.content.orEmpty()
                    ArtifactChange(
                        changeType = "created",
                        linesAdded = text.count { it == '\n' } + if (text.isNotBlank()) 1 else 0,
                        linesRemoved = 0,
                        diffExcerpt = null,
                    )
                }
                "workspace_edit_file" -> {
                    val oldText = element["old_text"]?.jsonPrimitive?.content.orEmpty()
                    val newText = element["new_text"]?.jsonPrimitive?.content.orEmpty()
                    val oldLines = oldText.lines().count { it.isNotBlank() }
                    val newLines = newText.lines().count { it.isNotBlank() }
                    ArtifactChange(
                        changeType = "modified",
                        linesAdded = newLines,
                        linesRemoved = oldLines,
                        diffExcerpt = newText.take(300).ifBlank { null },
                    )
                }
                else -> null
            }
        }.getOrNull()
}
