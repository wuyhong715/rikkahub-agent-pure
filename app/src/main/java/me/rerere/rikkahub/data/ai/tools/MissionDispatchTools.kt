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
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.service.run.ConversationRunRegistry
import me.rerere.rikkahub.service.run.RunOrchestrator
import me.rerere.rikkahub.service.run.RunRequest
import kotlin.uuid.Uuid

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
): List<Tool> = listOf(
    Tool(
        name = "mission_dispatch",
        description = """
            Run several independent subtasks **in parallel**, each as its own agent run with
            its own conversation and checkpoint.

            Use this when the work splits into parts that do not depend on each other (audit
            N modules, research M questions, generate K variants). Do NOT use it for steps
            that must happen in order — call tools sequentially for those.

            Every child inherits this conversation's assistant unless `assistant_id` is set.
            The call returns only after all children finish; each child's final text stays in
            its own conversation (use conversation_search/read to look it up if you need it).
        """.trimIndent(),
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
                                put("assistant_id", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Optional assistant uuid; defaults to this conversation's.")
                                })
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

            val missionId = runCatching { missionIdProvider() }.getOrNull()
            val requests = tasks.map { element ->
                val task = element.jsonObject
                val prompt = task["prompt"]?.jsonPrimitive?.contentOrNull
                    ?: error("each task needs a prompt")
                val assistantId = task["assistant_id"]?.jsonPrimitive?.contentOrNull
                    ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
                    ?: defaultAssistantId
                RunRequest(
                    assistantId = assistantId,
                    prompt = prompt,
                    title = task["title"]?.jsonPrimitive?.contentOrNull,
                    headless = true,
                    missionId = missionId,
                    kind = task["kind"]?.jsonPrimitive?.contentOrNull
                        ?.let { wire -> AgentRunKind.fromWire(wire) }
                        ?: AgentRunKind.SubAgent,
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
                        })
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
