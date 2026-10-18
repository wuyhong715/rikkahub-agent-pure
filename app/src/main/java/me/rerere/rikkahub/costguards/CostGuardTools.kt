package me.rerere.rikkahub.costguards

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.policy.PolicyEngine
import kotlin.uuid.Uuid

/**
 * Phase 15 — Cost & loop guards, v1 surface.
 *
 * One LLM tool: [checkTokenUsageTool]. Returns the running token totals for a given
 * conversation (defaults to the assistant's current chat) plus the assistant's soft /
 * hard token caps and a simple budget classification (UNDER_SOFT / WARN / OVER_HARD /
 * NO_BUDGET). The model is expected to self-throttle on WARN and stop on OVER_HARD.
 *
 * v2 (Phase 15.5) will add the live header pill + GenerationHandler-side auto-stop
 * integration. Ship the data surface first so the LLM can react in the meantime.
 *
 * Stuck-detection on screen-automation flows (the second half of Phase 15 per spec) is
 * its own Phase 15.7 — touches the screen-automation pipeline deeply and shipping a
 * partial version risks breaking the existing tap/swipe/scroll loop. Documented in
 * status.md.
 *
 * P2: the budget read now delegates to [PolicyEngine.tokenBudgetSnapshot] — the same
 * engine ChatService uses for auto-approval. The tool no longer takes SettingsStore /
 * ConversationRepository directly; the engine's DI-wired BudgetSnapshotSource resolves
 * the conversation (by id, or the assistant's most-recent when the id is omitted).
 *
 * Note on conversation_id: the pre-P2 tool echoed `conv.id` — the *resolved*
 * conversation's UUID. The engine returns a [TokenBudgetTracker.Snapshot] which does not
 * carry the id, so the payload now echoes the *input* parameter: the parsed UUID when one
 * was supplied, or the literal "current" when the call omitted it. The token numbers
 * themselves are identical — only the id label differs in the omitted-param case.
 */

private fun errEnv(error: String, detail: String): List<UIMessagePart> {
    val obj = buildJsonObject {
        put("error", error)
        put("detail", detail)
    }
    return listOf(UIMessagePart.Text(obj.toString()))
}

fun checkTokenUsageTool(
    policyEngine: PolicyEngine,
): Tool = Tool(
    name = "check_token_usage",
    description = """
        Read the running input + output token totals for a conversation and compare them
        against the assistant's soft / hard token-budget caps. Use to self-throttle on a
        long-running task: WARN means slow down or wrap up; OVER_HARD means stop and ask
        the user before continuing. Returns NO_BUDGET when no caps are configured (the
        defaults). If conversation_id is omitted, reports against the assistant's current
        chat. Read-only.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("conversation_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Conversation UUID; omit to use the assistant's current chat.")
                })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val rawConvId = params["conversation_id"]?.jsonPrimitive?.contentOrNull
        val convId = rawConvId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        // P2 — budget resolution converged into PolicyEngine. The engine resolves the
        // conversation (by id, or the assistant's most-recent when null) and returns the
        // snapshot. Null = no conversation could be resolved → the original error envelope.
        val snapshot = policyEngine.tokenBudgetSnapshot(convId)
            ?: return@Tool errEnv(
                "no_conversation",
                "no conversation found to compute token usage against"
            )
        val payload = buildJsonObject {
            // The snapshot does not carry the conversation id (see class KDoc). Echo the
            // input parameter so the caller can correlate the reply with the request;
            // "current" signals the engine picked the assistant's most-recent conversation.
            put("conversation_id", convId?.toString() ?: "current")
            put("input_tokens", snapshot.totals.inputTokens)
            put("output_tokens", snapshot.totals.outputTokens)
            put("total_tokens", snapshot.totals.totalTokens)
            put("per_message_max", snapshot.totals.perMessageMax)
            put("message_count", snapshot.totals.messageCount)
            if (snapshot.softCap != null) put("soft_cap", snapshot.softCap)
            if (snapshot.hardCap != null) put("hard_cap", snapshot.hardCap)
            put("status", snapshot.status.name)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
