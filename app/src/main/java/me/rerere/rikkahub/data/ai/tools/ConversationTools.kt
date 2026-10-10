package me.rerere.rikkahub.data.ai.tools

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
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.vector.ConversationIndexCoordinator
import me.rerere.rikkahub.data.vector.ConversationSearchEnvelope
import me.rerere.rikkahub.data.vector.DEFAULT_SEARCH_LIMIT
import me.rerere.rikkahub.data.vector.MAX_SEARCH_LIMIT
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate
import kotlin.uuid.Uuid

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
    conversationIndex: ConversationIndexCoordinator,
): List<Tool> = listOf(
    Tool(
        name = "recent_chats",
        description = """
            List the user's recent conversations with you to understand their preferences and ongoing topics.
            Returns conversation titles and the date of last activity, ordered by pinned first then most recently updated.
            Use this when you need quick context about what the user has been discussing lately.
            Only titles and dates are returned; use `conversation_search` to look up the actual content.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of recent conversations to return (default: 10, max: 30)"
                        )
                    })
                }
            )
        },
        execute = {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
            val recent = conversationRepo.getRecentConversations(
                assistantId = assistantId,
                limit = limit,
            )
            val payload = buildJsonArray {
                recent.forEach { conversation ->
                    add(buildJsonObject {
                        put("id", conversation.id.toString())
                        put("title", conversation.title.ifBlank { "Untitled" })
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "conversation_search",
        description = """
            Search the user's past conversations by meaning, and get back the passages that match,
            with the conversation and the date each one came from - including what was said in the
            current conversation a moment ago.

            Use this when you need something from before that you cannot quote - "what did we
            decide about the deployment", "the thing they mentioned about the streaming bug".
            Describe the conversation rather than guessing the words in it: this matches on
            meaning, so exact wording matters less than it used to.

            Each result names its conversation, so it can be opened or listed by other tools.

            Scores are cosine similarities and are NOT comparable between calls: compare the
            passages within one result set, and never treat a number as a relevance threshold.

            If it reports that semantic search is unavailable, no embedding model is installed.
            Say so to the user rather than retrying - there is no keyword fallback any more.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to search for in past conversation messages")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of results to return (default: 15, max: 50)"
                        )
                    })
                },
                required = listOf("query")
            )
        },
        execute = {
            val params = it.jsonObject
            val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (query.isEmpty()) {
                return@Tool listOf(UIMessagePart.Text(ConversationSearchEnvelope.missingQuery()))
            }
            // Clamped rather than refused, like the other searches: a model asking for fifty
            // passages is over-eager, not wrong, and failing the call costs it a round trip to
            // learn something the maximum already tells it.
            val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_SEARCH_LIMIT)
                .coerceIn(1, MAX_SEARCH_LIMIT)
            val response = conversationIndex.search(query, limit)
            val body = if (response.available) {
                ConversationSearchEnvelope.hits(
                    query = query,
                    results = response.results,
                    indexedMessages = response.indexedMessages,
                    stale = response.stale,
                )
            } else {
                ConversationSearchEnvelope.unavailable(response.note)
            }
            listOf(UIMessagePart.Text(body))
        }
    )
)
