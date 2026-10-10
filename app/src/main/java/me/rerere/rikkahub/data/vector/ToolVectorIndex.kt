package me.rerere.rikkahub.data.vector

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.ai.tools.ToolCatalog
import me.rerere.rikkahub.data.ai.tools.ToolCatalogEntry
import me.rerere.rikkahub.data.ai.tools.ToolRankFusion

/**
 * P3-02 — the impure half of semantic tool retrieval: hold a vector per catalogue entry, produce
 * the missing ones, and hand the ranked names back.
 *
 * Everything decidable without a model lives in [ToolRankFusion] (ranking, fusion, selection) and
 * [ToolVectorCacheRules] (identity, staleness); this class is the part that has to load a model,
 * survive concurrency and be cancellable. Keeping the split there is what lets the ranking
 * contract be tested on a bare JVM, which is where retrieval bugs actually live.
 *
 * ## The one rule that matters at call time
 *
 * **A search never waits for the catalogue to be embedded.** [retrieve] returns null the moment any
 * entry is not in the cache, and the caller falls back to its lexical ranking for that turn. The
 * tool call is on the critical path of a chat turn; stalling it for seconds to embed a few hundred
 * short strings is worse than answering it exactly-but-literally, because the vectors are being
 * warmed in the background anyway and the *next* turn gets the fused ranking.
 *
 * Vectors are held in memory only. They are derivable from (model, name, text), the catalogue is
 * small and changes with the MCP connections rather than with the conversation, and a persisted
 * copy would need the same invalidation story as the memory index for a fraction of the payoff.
 */
class ToolVectorIndex(
    private val embeddings: EmbeddingService,
    /** Where a background prewarm runs. The app scope: a catalogue does not belong to a screen. */
    private val scope: CoroutineScope,
) {

    /** Diagnostics for the assistant page. */
    data class Snapshot(val modelId: String?, val cached: Int) {
        val hasVector: Boolean get() = modelId != null
    }

    /**
     * Concurrent because reads happen on a search (hot path, no lock) while a prewarm writes.
     * A `HashMap` here would be a live race between the two, and the failure mode of that is a
     * corrupted map rather than a wrong ranking.
     */
    private val cache = ConcurrentHashMap<String, CachedToolVector>()

    /** Serialises embedding work so a prewarm and an explicit rebuild cannot duplicate it. */
    private val embeddingLock = Mutex()

    @Volatile
    private var activeModelId: String? = null

    /** What is held right now. */
    fun snapshot(): Snapshot = Snapshot(activeModelId, cache.size)

    /**
     * Embeds whatever [entries] are missing, and reports whether the catalogue is rankable
     * afterwards. False means "no model installed", "the model failed", or "an embedding failed" —
     * all of which the caller treats as "semantic search is off", not as an error.
     */
    suspend fun prepare(entries: List<ToolCatalogEntry>): Boolean {
        if (entries.isEmpty()) return false
        val model = embeddings.ensureLoaded() ?: return false

        val missing = embeddingLock.withLock {
            if (model.modelId != activeModelId) {
                // Every vector in here came out of a different vector space. Keeping them would
                // mean comparing incomparable numbers and calling the result relevance.
                cache.clear()
                activeModelId = model.modelId
            }
            ToolVectorCacheRules.missing(model.modelId, entries, cache)
        }
        if (missing.isEmpty()) return ToolVectorCacheRules.ready(model.modelId, entries, cache)

        val vectors = runCatching { embeddings.embed(missing.map(ToolVectorCacheRules::embedTextOf)) }
            .onFailure { Log.d(TAG, "embedding ${missing.size} tool entries failed", it) }
            .getOrNull()
            ?: return false

        missing.forEachIndexed { index, entry ->
            val vector = vectors.getOrNull(index) ?: return@forEachIndexed
            cache[ToolVectorCacheRules.key(model.modelId, entry.name)] =
                CachedToolVector(ToolVectorCacheRules.textHash(entry), vector)
        }
        return ToolVectorCacheRules.ready(model.modelId, entries, cache)
    }

    /**
     * Warms [entries] in the background. Cheap to call on every request: when the cache already
     * covers the catalogue this is a directory listing and a map scan.
     */
    fun prewarm(entries: List<ToolCatalogEntry>) {
        if (entries.isEmpty()) return
        scope.launch {
            runCatching { prepare(entries) }
                .onFailure { Log.d(TAG, "tool vector prewarm failed", it) }
        }
    }

    /**
     * The ranked names for [query], best first — or null when they cannot be produced right now
     * (no model, catalogue not embedded yet, embedding failed). Null is the fast, silent path back
     * to the caller's lexical ranking.
     */
    suspend fun rank(query: String, entries: List<ToolCatalogEntry>): List<String>? {
        val model = activeModelId ?: return null
        if (query.isBlank() || entries.isEmpty()) return null
        val vectors = entries.map { ToolVectorCacheRules.lookup(model, it, cache) }
        // One missing vector is enough to answer lexically: a partial ranking would silently
        // pretend the un-embedded tools were considered and found irrelevant.
        if (vectors.any { it == null }) return null
        val queryVector = runCatching { embeddings.embed(listOf(query.trim())).first() }
            .onFailure { Log.d(TAG, "embedding the tool query failed", it) }
            .getOrNull()
            ?: return null
        return ToolRankFusion.rankByCosine(queryVector, entries.map { it.name }, vectors.map { it ?: return null })
    }

    /**
     * The catalogue ranking `tool_search` should answer with, or null to leave it entirely to the
     * lexical scorer.
     *
     * The two channels are fused rather than chosen between: the lexical ranking is exact and free,
     * the vector ranking reaches tools whose names share nothing with the query, and
     * [ToolRankFusion.fuse] combines the *orderings* — which is the only part of a cosine here that
     * is trustworthy (see that file on why an absolute cutoff is not an option).
     */
    suspend fun retrieve(query: String, catalog: ToolCatalog): List<ToolCatalogEntry>? {
        val ranked = rank(query, catalog.entries) ?: return null
        val fused = ToolRankFusion.fuse(
            lexical = catalog.matchAll(query).map { it.name },
            vector = ranked,
        )
        return fused.mapNotNull { catalog.entry(it) }
    }

    private companion object {
        const val TAG = "ToolVectorIndex"
    }
}
