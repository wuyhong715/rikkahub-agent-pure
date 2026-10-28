package me.rerere.rikkahub.runtime

/**
 * Keeps the user's own words alive across a compaction.
 *
 * A compaction summary is lossy by construction: it is written by one model call under a token
 * target, and what survives is what that call judged important. In practice the thing that most
 * often does not survive is not the tool output (Moxw already carries a deterministic digest of
 * completed calls) but the *user's* wording — the constraint they mentioned once, the correction
 * they made three turns ago, the thing they said not to touch. Once the summary rewrites it, the
 * next turn cannot tell that a requirement existed.
 *
 * Kimi Code CLI solves this structurally rather than by instruction: its compaction keeps the user
 * entries themselves (a bounded head and tail) and summarizes only the assistant/tool history
 * (`human/compaction/shape.ts`). This is the same idea, adapted to Moxw's shape — Moxw's compacted
 * request is `[summary] + [raw tail]`, so the anchors slot in between as their own message:
 *
 * ```
 * [summary] [user anchors, verbatim] [raw tail]
 * ```
 *
 * Rules that matter:
 *
 * - The **first** user message is kept if it fits at all: it is the original task, and a summary
 *   that loses it is describing work whose purpose is gone.
 * - Then the **most recent** anchors are taken backwards, because a correction usually supersedes
 *   what came before it; stopping at the budget keeps the tail current rather than evenly sampled.
 * - Selection is by *user* message only. Assistant prose and tool output are already represented by
 *   the summary and the tool digest; duplicating them would spend the anchor budget on text the
 *   model can re-derive.
 * - The render is numbered so the model can refer to "requirement 2" instead of paraphrasing.
 *
 * Pure: the caller supplies token counts (see `ContextCompactionPlanner.estimateTokens`) and the
 * already-extracted text, so this file has no Android or LLM dependencies.
 */
data class AnchorCandidate(
    /** Position in the conversation, for stable ordering only. */
    val index: Int,
    /** The user's message text as they typed it. */
    val text: String,
    /** Estimated tokens for [text]; the caller's estimator decides. */
    val tokens: Int,
)

object CompactionAnchors {

    /**
     * Default anchor budget. Deliberately small relative to a summary target: anchors are a
     * guarantee, not an archive — if the budget were generous they would replace the summary
     * instead of anchoring it.
     */
    const val DEFAULT_BUDGET_TOKENS: Int = 4_000

    /** Skip absurdly small candidates rather than spending the render on a one-word message. */
    private const val MIN_CANDIDATE_TOKENS: Int = 1

    /**
     * Choose which anchors survive, in chronological order.
     *
     * @param candidates every user message that the summary is about to replace.
     * @param budgetTokens total tokens the anchors may occupy.
     */
    fun select(
        candidates: List<AnchorCandidate>,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
    ): List<AnchorCandidate> {
        if (candidates.isEmpty() || budgetTokens <= 0) return emptyList()
        val usable = candidates.filter { it.tokens in MIN_CANDIDATE_TOKENS..budgetTokens }
        if (usable.isEmpty()) return emptyList()

        val first = usable.first()
        var remaining = budgetTokens - first.tokens
        val chosen = linkedSetOf(first)

        // Most recent first: a later instruction supersedes an earlier one, and when the budget
        // runs out it is the older, already-superseded text we want to drop.
        for (candidate in usable.asReversed()) {
            if (candidate === first) continue
            if (candidate.tokens > remaining) continue
            chosen += candidate
            remaining -= candidate.tokens
            if (remaining <= 0) break
        }
        return chosen.sortedBy { it.index }
    }

    /**
     * Render the selected anchors as the message body placed after the summary.
     *
     * The header states what these lines are and why they are repeated, so the model treats them as
     * authoritative rather than as a duplicate of the summary it just read.
     */
    fun render(selected: List<AnchorCandidate>): String {
        if (selected.isEmpty()) return ""
        return buildString {
            appendLine("[User messages retained verbatim across the compaction above]")
            appendLine("These are the user's own words, not a paraphrase. Treat them as current requirements")
            appendLine("and constraints; where the summary disagrees, these win.")
            selected.forEachIndexed { position, anchor ->
                appendLine()
                appendLine("${position + 1}. ${anchor.text.trim()}")
            }
        }.trimEnd()
    }
}
