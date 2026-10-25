package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agentdef.AgentDefinition
import me.rerere.rikkahub.data.agentdef.AgentDefinitionResolver
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.service.run.ConversationRunRegistry
import me.rerere.rikkahub.service.run.RunOrchestrator
import me.rerere.rikkahub.service.run.RunRequest
import kotlin.uuid.Uuid

/** Hard ceiling on one call, matching what the description advertises. */
private const val MAX_TASKS = 6

/**
 * P6 — `mission_dispatch`: split the caller's work into parallel child runs.
 *
 * This is the model-facing half of multi-run orchestration. The model decomposes the task
 * into independent subtasks (the same judgement it already makes for `subagent_dispatch`),
 * hands them over in one call, and gets back one aggregate: per-subtask status plus the
 * conversation each child wrote into. Children are ledger-linked to the caller's run and
 * mission, so the workbench timeline shows the whole fan-out.
 *
 * Registered only when an orchestrator is available; never gated behind a setting, because
 * the tool is inert unless the model actually calls it.
 */
fun createMissionDispatchTools(
    orchestrator: RunOrchestrator,
    defaultAssistantId: Uuid,
    conversationId: Uuid?,
    missionIdProvider: suspend () -> String?,
    /**
     * P2-06b / P6 — the expert library snapshot. The SAME roster `subagent_dispatch` advertises,
     * so a parallel fan-out can use the experts a sequential dispatch can. Empty (the default)
     * = no `agent` parameter is offered and every child inherits the conversation's assistant,
     * exactly as before this parameter existed.
     */
    definitions: List<AgentDefinition> = emptyList(),
): List<Tool> = listOf(
    Tool(
        name = "mission_dispatch",
        description = buildString {
            append(
                """
                    Run several independent subtasks **in parallel**, each as its own agent run with
                    its own conversation and checkpoint.

                    Use this when the work splits into parts that do not depend on each other (audit
                    N modules, research M questions, generate K variants). Do NOT use it for steps
                    that must happen in order — call tools sequentially for those.

                    Every child inherits this conversation's assistant unless `agent` says otherwise. The call returns only after all children finish, and
                    each child's final text comes back in the aggregate (truncated); the full text
                    stays in the child's own conversation, reachable via conversation_search/read
                    if you need the remainder.
                """.trimIndent()
            )
            val experts = enabledExpertsOf(definitions)
            if (experts.isNotEmpty()) {
                appendLine()
                appendLine()
                append("Named experts (pass the name as each task's `agent`):\n")
                append(experts.joinToString("\n") { "- ${it.name}: ${it.description}" })
            }
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("tasks", buildJsonObject {
                        put("type", "array")
                        put("description", "Independent subtasks to run in parallel (1-6).")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("prompt", buildJsonObject {
                                    put("type", "string")
                                    put("description", "What this child run should do.")
                                })
                                put("title", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Short label for the run (shown on the timeline).")
                                })
                                if (enabledExpertsOf(definitions).isNotEmpty()) {
                                    put("agent", buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Optional expert name (case-insensitive). This child then " +
                                                "runs with that expert's model and system prompt. An " +
                                                "unknown name fails the whole call, and the error lists " +
                                                "the valid names.",
                                        )
                                    })
                                }
                                put("kind", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Ledger kind; defaults to subagent.")
                                })
                            })
                            put("required", buildJsonArray { add(JsonPrimitive("prompt")) })
                        })
                    })
                    put("max_concurrency", buildJsonObject {
                        put("type", "integer")
                        put("description", "How many children may run at once (1-6, default 3).")
                    })
                },
                required = listOf("tasks"),
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val tasks = params["tasks"]?.jsonArray ?: error("tasks is required")
            require(tasks.isNotEmpty()) { "tasks must not be empty" }
            // The schema advertises 1-6 and nothing enforced it: a model could ask for fifty
            // children and get fifty headless generations.
            require(tasks.size <= MAX_TASKS) { "at most $MAX_TASKS tasks per call (got ${tasks.size})" }

            val missionId = runCatching { missionIdProvider() }.getOrNull()
            val requests = tasks.map { element ->
                val task = element.jsonObject
                val prompt = task["prompt"]?.jsonPrimitive?.contentOrNull
                    ?: error("each task needs a prompt")
                // Deliberately NOT model-controllable any more. `assistant_id` let the model run
                // children as ANY assistant on the device — its workspace, its memories, its
                // granted MCP servers — a privilege escalation dressed as a parameter.
                val assistantId = defaultAssistantId
                // P6 — an expert, when named, supplies this child's model and prompt. Resolved
                // against the SAME enabled roster the description advertises, and a name that
                // matches nothing fails loudly instead of quietly running on the parent's
                // assistant (which would look like the expert had run).
                val expert = when (
                    val resolution = resolveMissionExpert(
                        name = task["agent"]?.jsonPrimitive?.contentOrNull,
                        experts = enabledExpertsOf(definitions),
                    )
                ) {
                    is MissionExpert.Inherit -> null
                    is MissionExpert.Resolved -> resolution.definition
                }
                RunRequest(
                    assistantId = assistantId,
                    prompt = prompt,
                    title = task["title"]?.jsonPrimitive?.contentOrNull,
                    headless = true,
                    missionId = missionId,
                    kind = task["kind"]?.jsonPrimitive?.contentOrNull
                        ?.let { wire -> AgentRunKind.fromWire(wire) }
                        ?: AgentRunKind.SubAgent,
                    systemPrompt = expert?.systemPrompt?.takeIf { it.isNotBlank() },
                    modelId = expert?.modelId?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() },
                )
            }

            val cap = params["max_concurrency"]?.jsonPrimitive?.intOrNull
                ?: RunOrchestrator.DEFAULT_MAX_CONCURRENCY

            val parentRunId = conversationId?.let { ConversationRunRegistry.runIdFor(it) }
            val batch = orchestrator.dispatch(
                requests = requests,
                missionId = missionId,
                parentRunId = parentRunId,
                maxConcurrency = cap,
            )

            val payload: JsonObject = buildJsonObject {
                put("requested", JsonPrimitive(batch.requested))
                put("completed", JsonPrimitive(batch.completed))
                put("all_completed", JsonPrimitive(batch.allCompleted))
                put("children", buildJsonArray {
                    batch.children.forEach { child ->
                        add(buildJsonObject {
                            put("index", JsonPrimitive(child.index))
                            put("label", JsonPrimitive(child.label))
                            child.runId?.let { put("run_id", JsonPrimitive(it)) }
                            child.conversationId?.let { put("conversation_id", JsonPrimitive(it)) }
                            child.status?.let { put("status", JsonPrimitive(it.name)) }
                            child.failure?.let { put("failure", JsonPrimitive(it)) }
                            child.result?.let { put("result", JsonPrimitive(it.truncateForParent())) }
                        })
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)

/**
 * P6 — how much of a child's final text rides back to the model in one aggregate.
 *
 * Six children at full length would cost more context than the fan-out saved; 1200 chars keeps
 * a typical four-section report intact and still bounds the whole reply. The remainder stays in
 * the child's conversation, which the aggregate points at.
 */
private const val CHILD_RESULT_MAX_CHARS = 1200

private fun String.truncateForParent(): String = if (length <= CHILD_RESULT_MAX_CHARS) {
    this
} else {
    take(CHILD_RESULT_MAX_CHARS) + "… [truncated — full text in the child's conversation]"
}

/** P6 — what one mission task's `agent` resolved to. */
internal sealed class MissionExpert {
    /** No `agent` given: the child inherits the conversation's assistant, exactly as before. */
    data object Inherit : MissionExpert()

    /** The named expert, matched case-insensitively against the enabled roster. */
    data class Resolved(val definition: AgentDefinition) : MissionExpert()
}

/**
 * P6 — resolve one task's `agent` against the enabled expert roster.
 *
 * Pure, so the matching rule is unit-testable without a live [Tool] — the same rationale the
 * other extracted rules in this codebase use. The failure case is deliberate: a name that
 * matches nothing throws instead of falling back to inheriting the assistant, because a child
 * that ran on the wrong prompt would still report back as if the expert had produced it.
 */
internal fun resolveMissionExpert(name: String?, experts: List<AgentDefinition>): MissionExpert {
    val wanted = name?.trim()?.takeIf { it.isNotEmpty() } ?: return MissionExpert.Inherit
    return experts.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
        ?.let { MissionExpert.Resolved(it) }
        ?: error(
            "unknown expert \"$wanted\"; available: " +
                experts.joinToString(", ") { it.name }
        )
}

/**
 * P6 — the enabled expert roster, recomputed per call site rather than hoisted: this tool's
 * body is an expression (`listOf(...)`), so threading one more val through it would re-indent
 * the whole definition for a filter over a handful of rows.
 */
private fun enabledExpertsOf(definitions: List<AgentDefinition>): List<AgentDefinition> =
    AgentDefinitionResolver.enabledDefinitions(definitions)
