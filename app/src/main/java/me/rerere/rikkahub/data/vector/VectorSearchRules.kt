package me.rerere.rikkahub.data.vector

import me.rerere.llamacpp.VectorMath

/** One retrieved item and its similarity to the query. */
data class ScoredChunk<T>(val item: T, val score: Float)

/**
 * The outcome of a search, including how much of the index was unusable.
 *
 * [skipped] is carried rather than logged and forgotten because a non-zero value means the index
 * and the active model have drifted apart - chunks embedded by a previous model are mixed into
 * the same table. That is a recoverable state (a re-index fixes it), but it has to be visible,
 * otherwise search just quietly gets worse.
 */
data class VectorSearchResult<T>(val hits: List<ScoredChunk<T>>, val skipped: Int)

/**
 * Ranks candidates against a query vector.
 *
 * Brute force on purpose: 768 dimensions by ten thousand chunks is about eight million
 * multiply-adds, i.e. single-digit milliseconds with the vectors in memory, which is well below
 * the cost of the embedding call that produced the query. An approximate-nearest-neighbour
 * structure would add a build step, a recall/latency trade-off to tune, and a persistence
 * format of its own, to save a millisecond. Revisit at a hundred thousand chunks, not before.
 *
 * ## Why there is no absolute score threshold
 *
 * Embedding models do not share a similarity scale. Measured on EmbeddingGemma 2, two unrelated
 * sentences ("the cat sat on the mat" / "quarterly tax filing deadlines") still score 0.56, and
 * an unrelated noun pair ("猫" / "汽车发动机") scores 0.78 - so any hand-picked constant like
 * "keep hits above 0.8" is a fact about one model's output distribution, not about relevance.
 * The knob here is therefore *relative to the best hit in this very query*
 * ([relativeFloor]), which means the same number keeps working when the model is swapped.
 *
 * Pure, so the ranking policy is unit-tested rather than argued about.
 */
object VectorSearchRules {

    /**
     * Fraction of the best hit's score a chunk must reach to be returned.
     *
     * 0 means "return the top [limit] and let the caller decide"; the usual setting is around
     * 0.9, which drops the long tail of near-miss chunks while never dropping the best hit.
     * Only meaningful for non-negative scores, so the filter is applied only when the best
     * score is positive.
     */
    const val DEFAULT_RELATIVE_FLOOR = 0f

    fun <T> rank(
        query: FloatArray,
        candidates: List<Pair<T, FloatArray>>,
        limit: Int,
        relativeFloor: Float = DEFAULT_RELATIVE_FLOOR,
    ): VectorSearchResult<T> {
        require(limit > 0) { "limit must be positive" }
        require(query.isNotEmpty()) { "the query vector is empty" }
        require(relativeFloor in 0f..1f) { "relativeFloor must be in [0, 1]" }

        var skipped = 0
        val scored = ArrayList<ScoredChunk<T>>(candidates.size)
        for ((item, vector) in candidates) {
            // A width that differs from the query's cannot be scored at all. Skipping (rather
            // than throwing) keeps search alive during the window where a model has just been
            // replaced and the re-index has not caught up; the count is reported upward.
            if (vector.size != query.size) {
                skipped++
                continue
            }
            scored += ScoredChunk(item, VectorMath.cosine(query, vector))
        }

        scored.sortByDescending { it.score }
        if (scored.size > limit) scored.subList(limit, scored.size).clear()

        val best = scored.firstOrNull()?.score ?: 0f
        val filtered = if (relativeFloor > 0f && best > 0f) {
            scored.filter { it.score >= best * relativeFloor }
        } else {
            scored
        }
        return VectorSearchResult(hits = filtered, skipped = skipped)
    }
}
