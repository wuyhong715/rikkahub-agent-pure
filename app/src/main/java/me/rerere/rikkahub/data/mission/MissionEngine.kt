package me.rerere.rikkahub.data.mission

import android.util.Log
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.subagent.SubAgentRun
import me.rerere.rikkahub.subagent.SubAgentStatus
import me.rerere.rikkahub.runtime.util.JsonInstant
import me.rerere.rikkahub.service.run.ConversationRunRegistry
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
    /**
     * The ledger run currently driving [conversationId], when it can be established.
     *
     * Two sources, best first:
     *  1. [ConversationRunRegistry] - the run generating right now. [RunService] binds it for
     *     every kind of run, headless or not, so this is the precise answer.
     *  2. `mission.lastRunId` - after a process death or an approval pause the live binding is
     *     gone, but the mission still points at the run it belongs to.
     *
     * Null is a legitimate answer (a plain chat inside a mission-bound conversation has no run);
     * callers must treat it as "cannot attribute", never as an error.
     */
    private suspend fun runIdFor(conversationId: String, missionId: String?): String? {
        val conversation = runCatching { Uuid.parse(conversationId) }.getOrNull()
        if (conversation != null) {
            val bound = ConversationRunRegistry.runIdFor(conversation)
            if (bound != null) return bound
        }
        if (missionId == null) return null
        val lastRunId: String? =
            runCatching { repository.getById(missionId)?.lastRunId }.getOrNull()
        return lastRunId
    }

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
            // Both journal kinds below are read back *per run* by MissionSupervisor, whose query
            // is `WHERE run_id = :runId`. An event written without a run id is therefore
            // invisible there: hasProgress stays false, a round that ran ten tools and rewrote
            // task-state.md still scores as "no progress", the stall counter climbs and the
            // mission is eventually parked as BLOCKED. Resolve it once, use it for both.
            val missionId = missionIdFor(conversationId)
            val runId = runIdFor(conversationId, missionId)

            // (a) TOOL_RESULT — unconditional, one row per tool execution.
            runEventRepository.emit(
                type = RunEventType.TOOL_RESULT,
                title = toolName,
                detail = outputJson.take(300).ifBlank { null },
                payloadJson = JsonInstant.encodeToString(
                    buildJsonObject {
                        put("tool", toolName)
                        put("args", argsJson.take(500))
                        // 批 I — 失败归因：分类结果进 payload，供督导统计与下一轮交接使用。
                        // 分类函数是纯文本判定，认不出来就是 null，不影响事件本身的写入。
                        val failure = me.rerere.rikkahub.runtime.tools.ToolFailureClassifier.detect(outputJson)
                        if (failure != null) {
                            put("failed", true)
                            put("failureKind", failure.kind.name)
                            if (failure.detail.isNotBlank()) put("failureDetail", failure.detail)
                        }
                    }
                ),
                missionId = missionId,
                conversationId = conversationId,
                runId = runId,
            )

            // (b) ARTIFACT_TOOLS whitelist — attach file artifacts to the bound mission,
            // with change metadata (diff summary) so the workbench can render a review
            // view without re-reading files (batch 1: delivery closure).
            if (toolName !in ARTIFACT_TOOLS) return@runCatching
            val path = parsePath(argsJson) ?: return@runCatching
            val boundMissionId = missionId ?: return@runCatching
            val change = computeArtifactChange(toolName, argsJson)
            repository.attachArtifact(boundMissionId, path, toolName, change = change, runId = runId)
        }.onFailure { Log.w(TAG, "onToolExecuted failed for $toolName", it) }
    }

    /**
     * 批 E — the generation loop just parked this turn because [toolName] needs the user's
     * approval. Flips a RUNNING mission to WAITING_APPROVAL and journals a NOTE, so the
     * workbench shows "waiting for approval" instead of a run that looks alive but idle.
     *
     * Idempotent by posture: it only acts on RUNNING, so a second pending tool in the same
     * step (or a replay) cannot duplicate the event, and a mission the user already stopped
     * (IDLE) is never resurrected by a late callback. Best-effort like every engine hook.
     */
    suspend fun onAwaitingApproval(conversationId: String, toolName: String) {
        runCatching {
            val missionId = missionIdFor(conversationId) ?: return@runCatching
            val mission = repository.getById(missionId) ?: return@runCatching
            if (mission.executionStateEnum != MissionExecutionState.RUNNING) return@runCatching
            repository.updateExecutionState(missionId, MissionExecutionState.WAITING_APPROVAL)
            repository.logEvent(
                missionId = missionId,
                type = RunEventType.NOTE,
                title = "awaiting_approval",
                detail = toolName,
                conversationId = conversationId,
                runId = mission.lastRunId,
            )
        }.onFailure { Log.w(TAG, "onAwaitingApproval failed for $toolName", it) }
    }

    /**
     * 批 E — the user (or a timeout path) resolved the pending approval: flip a mission that
     * is WAITING_APPROVAL back to RUNNING. Only that posture is touched — an IDLE mission
     * stays parked, and a run without a bound mission is a silent no-op.
     */
    suspend fun onApprovalResolved(conversationId: String) {
        runCatching {
            val missionId = missionIdFor(conversationId) ?: return@runCatching
            val mission = repository.getById(missionId) ?: return@runCatching
            if (mission.executionStateEnum != MissionExecutionState.WAITING_APPROVAL) return@runCatching
            repository.updateExecutionState(missionId, MissionExecutionState.RUNNING)
        }.onFailure { Log.w(TAG, "onApprovalResolved failed", it) }
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
     * 批 A — persist one `mission_report` signal from the model's signal tool.
     *
     * Called by the `mission_report` tool assembly (ChatService) with the current
     * conversation + run coordinates. Writes a NOTE event in the exact shape the
     * MissionSupervisor consumes for its continue/stop decision:
     *
     *   type = NOTE, title = "mission_report: {status}",
     *   payloadJson = {"mission_signal": "{status}", "note": "..."} (note omitted when blank)
     *
     * `status` is expected to be `done`, `blocked` or `progress`; anything else is stored
     * verbatim (the supervisor reads only the three known values).
     *
     * Best-effort like every other method here — reporting a signal must never break the
     * generation loop, and a conversation with no bound mission is a silent no-op.
     *
     * @param missionId the mission the caller resolved while assembling the tool. Passing it
     *   makes the write independent of the DB backfill having caught up (the first round can
     *   report before `missions.conversation_id` is written) and of the process-scoped cache
     *   being warm (a boot-resumed round starts with an empty cache).
     */
    suspend fun reportSignal(
        conversationId: String,
        runId: String?,
        status: String,
        note: String?,
        missionId: String? = null,
        goals: List<Int> = emptyList(),
    ) {
        runCatching {
            val missionId = missionId ?: missionIdFor(conversationId) ?: return@runCatching
            // Keep the cache in sync with the resolved coordinate so later timeline writes in
            // the same round find the mission without another DB read.
            bindings[conversationId] = missionId
            runEventRepository.emit(
                type = RunEventType.NOTE,
                title = "mission_report: $status",
                detail = note?.takeIf { it.isNotBlank() },
                payloadJson = JsonInstant.encodeToString(
                    buildJsonObject {
                        put("mission_signal", status)
                        note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
                        // 批 B.5 — self-assessment: which goals the model says it met.
                        // Absent means "all of them" (see MissionSupervisor.complete).
                        if (goals.isNotEmpty()) {
                            put(
                                "goals",
                                buildJsonArray { goals.sorted().forEach { add(JsonPrimitive(it)) } },
                            )
                        }
                    }
                ),
                missionId = missionId,
                conversationId = conversationId,
                runId = runId,
            )
        }.onFailure { Log.w(TAG, "reportSignal failed for $conversationId", it) }
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
