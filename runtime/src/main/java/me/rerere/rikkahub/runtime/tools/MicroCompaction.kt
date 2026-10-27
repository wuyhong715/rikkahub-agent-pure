package me.rerere.rikkahub.runtime.tools

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Rolls stale tool output out of the **request**, without touching the conversation.
 *
 * A long turn accumulates tool results far faster than anything else: a single investigation
 * can leave 40 results of 1–3k tokens each in the history, and from then on every model
 * request re-sends all of them. The existing compaction only fires near the window limit
 * (80% by default, and it is the model that has to act on the reminder), so the whole middle
 * of a long task runs with a prefix that is mostly output the model has already acted on.
 *
 * This is the smaller, earlier move: once the request's tool output passes
 * [TRIGGER_TOKENS], every result *older* than the most recent [KEEP_RECENT_CALLS] that is
 * big enough to matter ([MIN_RESULT_TOKENS]) is replaced by one line naming the call, its
 * size, and where the full text went. The recent window — the results the model is actually
 * working from — is untouched.
 *
 * What is deliberately **not** compacted:
 *
 * - the most recent calls, and anything small enough that the notice would cost as much as
 *   the content;
 * - failed results (`{"error": …}`): the model may still be re-planning around them;
 * - `tool_output_read` pages: compacting one produces a notice pointing at another file,
 *   which is the same endless ladder the truncation gate already refuses to climb.
 *
 * The projection is **request-only**: callers pass the conversation's messages and use the
 * result for one request. Nothing is written back, so the chat keeps showing the real output
 * and the next request re-derives the same projection from the same input — a stable prefix
 * for prompt caching. The same `toolCallId` always spills to the same file name, so repeated
 * requests do not grow the spill directory.
 *
 * Pure except for the injected [spill] callback, which keeps the whole table unit-testable.
 */
object MicroCompaction {

    /** How many of the newest tool calls always keep their full output. */
    const val KEEP_RECENT_CALLS: Int = 6

    /** A result smaller than this is not worth a notice — it would save almost nothing. */
    const val MIN_RESULT_TOKENS: Int = 600

    /** Total tool output (estimated tokens) below which nothing is compacted at all. */
    const val TRIGGER_TOKENS: Int = 16_000

    /** First characters of a compacted result; also the marker [isCompacted] looks for. */
    private const val MARKER = "[tool output compacted]"

    /**
     * @param messages the request's messages, oldest first.
     * @param spill persists a result's full text and returns the file name, or `null` when
     *   there is nowhere to put it (no workspace). A `null` still compacts, but the notice
     *   can only tell the model to re-run the call.
     */
    fun project(
        messages: List<UIMessage>,
        keepRecentCalls: Int = KEEP_RECENT_CALLS,
        minResultTokens: Int = MIN_RESULT_TOKENS,
        triggerTokens: Int = TRIGGER_TOKENS,
        spill: (toolCallId: String, fullText: String) -> String? = { _, _ -> null },
    ): List<UIMessage> {
        if (messages.isEmpty()) return messages

        data class Slot(
            val messageIndex: Int,
            val partIndex: Int,
            val tool: UIMessagePart.Tool,
            val tokens: Int,
        )

        val slots = ArrayList<Slot>()
        var totalTokens = 0
        messages.forEachIndexed { messageIndex, message ->
            message.parts.forEachIndexed { partIndex, part ->
                if (part !is UIMessagePart.Tool || part.output.isEmpty()) return@forEachIndexed
                val text = textOf(part)
                if (text.isEmpty()) return@forEachIndexed
                val tokens = TokenEstimator.estimate(text)
                totalTokens += tokens
                slots += Slot(messageIndex, partIndex, part, tokens)
            }
        }

        if (totalTokens < triggerTokens) return messages
        if (slots.size <= keepRecentCalls) return messages

        val replaceable = slots.dropLast(keepRecentCalls).filter { slot ->
            slot.tokens >= minResultTokens &&
                slot.tool.toolName != ToolOutputRules.READ_TOOL_NAME &&
                !reportsFailure(slot.tool) &&
                !isCompacted(slot.tool)
        }
        if (replaceable.isEmpty()) return messages

        val byMessage = HashMap<Int, MutableMap<Int, UIMessagePart>>()
        for (slot in replaceable) {
            val text = textOf(slot.tool)
            val file = runCatching { spill(slot.tool.toolCallId, text) }.getOrNull()
            val notice = UIMessagePart.Text(
                buildString {
                    append(MARKER)
                    append(' ')
                    append(slot.tool.toolName)
                    append(" → ~")
                    append(slot.tokens)
                    append(" tokens, elided from this request")
                    if (file != null) {
                        append("; full text: /tool_outputs/")
                        append(file)
                        append(" (read it with ")
                        append(ToolOutputRules.READ_TOOL_NAME)
                        append(" if you need it)")
                    } else {
                        append("; re-run the call if you need it again")
                    }
                    append('.')
                }
            )
            // The call itself, its approval state and its execution timestamp stay: only the
            // payload shrinks, so the request still shows what ran and that it succeeded.
            byMessage.getOrPut(slot.messageIndex) { HashMap() }[slot.partIndex] =
                slot.tool.copy(output = listOf(notice))
        }

        return messages.mapIndexed { messageIndex, message ->
            val replacements = byMessage[messageIndex] ?: return@mapIndexed message
            message.copy(
                parts = message.parts.mapIndexed { partIndex, part ->
                    replacements[partIndex] ?: part
                },
            )
        }
    }

    /** Whether this result is already a [project] notice (re-projection stays idempotent). */
    fun isCompacted(tool: UIMessagePart.Tool): Boolean =
        tool.output.size == 1 &&
            (tool.output[0] as? UIMessagePart.Text)?.text?.startsWith(MARKER) == true

    private fun textOf(tool: UIMessagePart.Tool): String =
        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    /**
     * Failure envelopes carry an `error` key; the same test [ReasoningDepthPolicy] uses. A
     * quoted key rather than the bare word, so ordinary output mentioning "error" is not
     * mistaken for a failed call.
     */
    private fun reportsFailure(tool: UIMessagePart.Tool): Boolean =
        tool.output.filterIsInstance<UIMessagePart.Text>().any { "\"error\"" in it.text }
}
