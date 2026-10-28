package me.rerere.rikkahub.runtime.tools

/**
 * Caps oversized tool results that survive a compaction.
 *
 * After a compaction Moxw keeps a raw tail verbatim (the summary covers everything before it). That
 * tail is usually not "many messages" — it is *a few enormous ones*: one full-file read, one shell
 * dump, one log scrape. They sit in every subsequent request, at full size, until the next
 * compaction; they are also the reason the tail can land just under the trigger and cross it again
 * on the next tool call.
 *
 * DeepSeek Harness solves this with a pruner that rewrites old tool results as head + marker + tail
 * *without calling a model* (`compaction-tool-result-pruner`). Moxw already does an equivalent at
 * write time (the result is truncated and spilled when produced), so re-truncating the whole
 * history would be wasted work. The one place it does not cover is the tail after a compaction:
 * those results were small enough to pass the write-time budget individually, and only become a
 * problem in aggregate — or they were written before a smaller budget was configured.
 *
 * This is the deterministic half: no budget arithmetic, no per-turn variance. A result above
 * [CAP_TOKENS] is capped to [TARGET_TOKENS], always, by the same rule — which keeps the request
 * prefix stable across turns (a size-dependent rule would re-render on every call and bust the
 * provider's cache).
 *
 * The model is told what happened, so a cap cannot silently become data loss: the replacement says
 * the output was shortened and that running the tool again returns it in full.
 */
data class PrunableToolOutput(
    /** Position within the list being pruned; used to rebuild it deterministically. */
    val index: Int,
    /** Estimated tokens of [text]. */
    val tokens: Int,
    val text: String,
)

data class PrunedToolOutput(
    val index: Int,
    val replacement: String,
    val reclaimedTokens: Int,
)

object ToolOutputPruner {

    /** Results at or below this size are left exactly as they are. */
    const val CAP_TOKENS: Int = 8_000

    /** What an oversized result is reduced to (head + tail, ratio from [DEFAULT_TOOL_RESULT_HEAD_RATIO]). */
    const val TARGET_TOKENS: Int = 3_000

    /**
     * Plan the caps for [candidates]. Deterministic: the same input yields the same output, which
     * is what keeps the post-compaction request prefix stable.
     */
    fun plan(
        candidates: List<PrunableToolOutput>,
        capTokens: Int = CAP_TOKENS,
        targetTokens: Int = TARGET_TOKENS,
    ): List<PrunedToolOutput> {
        if (capTokens <= 0 || targetTokens <= 0 || targetTokens >= capTokens) return emptyList()
        return candidates.mapNotNull { candidate ->
            if (candidate.tokens <= capTokens) return@mapNotNull null
            val outcome = truncateToolResult(candidate.text, maxTokens = targetTokens)
            if (!outcome.truncated) return@mapNotNull null
            PrunedToolOutput(
                index = candidate.index,
                replacement = notice(candidate.tokens, outcome.resultTokens) + "\n\n" + outcome.text,
                reclaimedTokens = (candidate.tokens - outcome.resultTokens).coerceAtLeast(0),
            )
        }
    }

    /**
     * The line the model reads before the shortened body.
     *
     * It states the loss, the reason, and the recovery route. Without the recovery route a model
     * that needs a value from the middle of the output will guess instead of re-reading.
     */
    internal fun notice(originalTokens: Int, resultTokens: Int): String =
        "[This earlier tool result was shortened to keep the context small: " +
            "$originalTokens → $resultTokens tokens, middle elided. " +
            "Run the tool again if you need the full output.]"
}
