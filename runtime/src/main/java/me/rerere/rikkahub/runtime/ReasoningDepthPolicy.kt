package me.rerere.rikkahub.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Picks the thinking depth for one model request from the assistant's configured level and
 * the shape of the conversation so far.
 *
 * The policy exists for one case: **a tool follow-up turn**. When the last message is an
 * assistant message whose tool calls already produced output, the model's job is "read this
 * output and emit the next call" — there is nothing left to plan. Providers treat
 * [ReasoningLevel.AUTO] as "decide for me", and their default is sized for reasoning about a
 * problem: measured on a flash model, these turns spent 20–960 thinking tokens each, i.e. one
 * to seventeen seconds at ~55 tok/s, without changing the next action.
 *
 * So AUTO is narrowed to OFF in that one case. Everything else is untouched:
 *
 * - an explicit level (OFF/LOW/MEDIUM/HIGH/XHIGH) is always returned as-is;
 * - a non-assistant last message (the user just spoke) keeps AUTO;
 * - a call still waiting for approval has no output, so the turn is not a follow-up;
 * - **a failed previous step keeps AUTO** — the model genuinely has to re-plan there, and
 *   suppressing its thinking is exactly the wrong trade.
 *
 * Pure and dependency-free so the whole table is unit-testable.
 */
object ReasoningDepthPolicy {

    fun forRequest(
        configured: ReasoningLevel,
        messages: List<UIMessage>,
    ): ReasoningLevel {
        if (configured != ReasoningLevel.AUTO) return configured

        val last = messages.lastOrNull() ?: return configured
        if (last.role != MessageRole.ASSISTANT) return configured

        val calls = last.parts.filterIsInstance<UIMessagePart.Tool>()
        if (calls.isEmpty()) return configured
        if (calls.any { it.output.isEmpty() }) return configured
        if (calls.any(::reportsFailure)) return configured
        return ReasoningLevel.OFF
    }

    /**
     * Tool failures reach the model as a JSON envelope with an `error` key (every failure
     * path in the loop emits one). Checking for the quoted key rather than the bare word
     * keeps ordinary output that happens to contain "error" from being read as a failure.
     */
    private fun reportsFailure(call: UIMessagePart.Tool): Boolean =
        call.output.filterIsInstance<UIMessagePart.Text>().any { "\"error\"" in it.text }
}
