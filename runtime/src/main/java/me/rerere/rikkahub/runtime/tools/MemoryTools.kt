package me.rerere.rikkahub.runtime.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.runtime.LoopMemory
import me.rerere.rikkahub.runtime.LoopMemoryWrite
import me.rerere.rikkahub.runtime.util.toLocalString
import java.time.LocalDate

/** The model-visible echo of a memory row. Field set matches :app's AssistantMemory
 *  serializer ({id, content}) so the tool output models have been trained on is unchanged. */
private fun memoryPayload(memory: LoopMemory) = buildJsonObject {
    put("id", memory.id)
    put("content", memory.content)
}

/** The one model-facing memory tool. Named here so the wrappers that must not double-add it
 *  (the loop's own injection, the sub-agent surface) agree with the builder. */
const val MEMORY_TOOL_NAME = "memory_tool"

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String, MemoryKind) -> LoopMemoryWrite,
    onUpdate: suspend (Int, String) -> LoopMemory,
    onDelete: suspend (Int) -> Unit
): List<Tool> = listOf(
    Tool(
        name = MEMORY_TOOL_NAME,
        description = """
            The memory tool stores long-term information across conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove).
            - No relevant record: `create` + `content` (+ `kind`)
            - Existing relevant record: `edit` + `id` + `content`
            - Outdated/irrelevant record: `delete` + `id`

            `kind` tells the app how the record should be treated. Omitting it stores a `note`.
            - preference: how the user wants to be talked to, what they like or dislike
            - fact: a durable fact about the user, their setup or their work
            - plan: something the user intends to do
            - state: where things stand right now (stops being sent after ~30 days unless refreshed)
            - note: anything else, including classified-nowhere records

            A `create` can be REFUSED when the same thing is already stored. The result then has
            `stored=false`, a `reason`, a `hint` and the `existing` record. When that happens, call
            `edit` with the existing id instead of retrying `create` — do not delete and recreate.
            Memories will automatically appear in the <memories> tag in later conversations.
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).
            You may store: preferred name, preferences, plans, work-related notes, chat style preferences, first chat time, etc.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Today is ${LocalDate.now().toLocalString(true)}.
            Similar memories should be merged; prefer updating existing records.

            Examples:
            {"action":"create","content":"User prefers brief replies and is more active on weekends."}
            {"action":"edit","id":12,"content":"User’s preferred name updated to “A-Xing”, prefers Chinese replies."}
            {"action":"delete","id":7}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                            }
                        )
                        put("description", "Operation to perform: create, edit, or delete")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                    put("kind", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray { MemoryKind.wireNames().forEach { add(it) } }
                        )
                        put(
                            "description",
                            "Only for `create`. What sort of record this is; see the tool " +
                                "description. Defaults to `note`."
                        )
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    val kind = MemoryKind.fromWire(params["kind"]?.jsonPrimitive?.contentOrNull)
                    when (val write = onCreation(content, kind)) {
                        is LoopMemoryWrite.Stored -> memoryPayload(write.memory)
                        // A refusal is a normal result: the model gets the existing id and can
                        // correct itself in this same turn, which is cheaper than a tool_failed
                        // envelope it has no way to act on.
                        is LoopMemoryWrite.Rejected -> buildJsonObject {
                            put("stored", false)
                            put("reason", write.reason)
                            put("hint", write.hint)
                            write.existing?.let { existing -> put("existing", memoryPayload(existing)) }
                        }
                    }
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    memoryPayload(onUpdate(id, content))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
