package me.rerere.rikkahub.data.vector

/**
 * The file library as an indexed source.
 *
 * A sibling of [MemoryVectorSource] rather than a special case of it, because the two answer
 * different questions about what a document is. Cold memory indexes Markdown notes by *name* - the
 * name is the identity, and a hit is handed straight back to `memory_read`. The library indexes
 * whatever the user keeps in a directory, by *path*, chunked according to what kind of file it is
 * (code, prose, or an extracted document), and a hit is handed to `workspace_read_file`.
 *
 * What they share is the index: the same [VectorIndexStore], keyed by source. The scope is in the
 * key (`library:<workspace>:<dir>`) for the same reason it is in cold memory's - the library
 * belongs to a workspace and a directory, and two of either must never see each other's rows.
 */
class WorkspaceLibrarySource(
    private val store: VectorIndexStore,
    private val embeddings: EmbeddingService,
) {

    /**
     * One file of the library, already read. [chunkMode] travels with the text because it is
     * decided from the file name (see [WorkspaceLibraryRules.chunkModeOf]) while the text is what
     * the walk managed to extract.
     */
    data class Doc(val path: String, val text: String, val chunkMode: ChunkingMode)

    data class SyncReport(
        val documents: Int,
        val indexed: Int,
        val unchanged: Int,
        val skipped: Int,
        val chunks: Int,
        val embeddedChunks: Int,
        /** Set when nothing could be done; the message is meant for a log line or a UI hint. */
        val reason: String? = null,
    )

    /**
     * Brings the index in line with what this round read.
     *
     * [keep] is every path the walk *saw*, whether or not this round read it. It exists because
     * the library - unlike cold memory - deliberately reads only part of the directory per round:
     * a file deferred by the budget, or one that failed to read this time, is absent from [docs]
     * but is emphatically not deleted. Without [keep], the second round would delete the first
     * round's work. A path missing from both is taken as deleted, which is the only evidence of
     * deletion available.
     */
    suspend fun sync(
        workspaceId: String,
        dir: String,
        docs: List<Doc>,
        keep: Set<String>,
        nowMs: Long,
    ): SyncReport {
        val model = embeddings.ensureLoaded()
            ?: return SyncReport(
                documents = docs.size,
                indexed = 0,
                unchanged = 0,
                skipped = 0,
                chunks = 0,
                embeddedChunks = 0,
                reason = "no embedding model is installed",
            )

        val source = sourceOf(workspaceId, dir)
        var indexed = 0
        var unchanged = 0
        var skipped = 0
        var chunks = 0
        var embedded = 0

        for (doc in docs) {
            val fresh = TextChunker.chunk(doc.text, ChunkSpec(mode = doc.chunkMode))
            if (fresh.isEmpty()) {
                skipped++
                continue
            }
            val plan = store.plan(source, doc.path, fresh)
            if (IndexSyncRules.isNoOp(plan)) {
                unchanged++
                continue
            }
            val written = store.apply(source, doc.path, plan, model.modelId, nowMs) { texts ->
                embeddings.embed(texts)
            }
            plan as SyncPlan.Replace
            chunks += written
            embedded += plan.chunks.size - plan.reuse.size
            indexed++
        }

        // Files the walk no longer mentions: the only available evidence that they are gone.
        // `keep` covers everything the walk did see, so a file this round merely deferred (or
        // failed to read) keeps its rows and its place.
        val seen = docs.map { it.path }.toSet() + keep
        WorkspaceLibraryRules.toForget(store.docKeys(source), seen)
            .forEach { store.forget(source, it) }

        // A source key carries the directory, so moving the library would otherwise strand the
        // previous directory's rows: invisible to search (which filters on the current key) but
        // still occupying the database. Dropped here, once a sync has proved it runs at all.
        store.sourcesWithPrefix(workspacePrefix(workspaceId))
            .filter { it != source }
            .forEach { store.forgetSource(it) }

        return SyncReport(
            documents = docs.size,
            indexed = indexed,
            unchanged = unchanged,
            skipped = skipped,
            chunks = chunks,
            embeddedChunks = embedded,
        )
    }

    /**
     * Semantic search over the same directory.
     *
     * Returns no hits - rather than throwing - when no model is installed, because "semantic search
     * is not set up" is a normal state and the tool that calls this has to explain it either way.
     */
    suspend fun search(
        workspaceId: String,
        dir: String,
        query: String,
        limit: Int = VectorIndexStore.DEFAULT_LIMIT,
        relativeFloor: Float = VectorSearchRules.DEFAULT_RELATIVE_FLOOR,
    ): LibrarySearchOutcome {
        val source = sourceOf(workspaceId, dir)
        val model = embeddings.ensureLoaded()
            ?: return LibrarySearchOutcome(available = false)
        val queryVector = embeddings.embedOne(query)
        val result = store.search(source, model.modelId, queryVector, limit, relativeFloor)
        val models = store.modelIdsOf(source)
        return LibrarySearchOutcome(
            available = true,
            hits = result.asHits().map { chunk ->
                LibrarySearchHit(
                    path = chunk.docKey,
                    chunkIndex = chunk.chunkIndex,
                    score = chunk.score,
                    text = chunk.text,
                )
            },
            indexedFiles = store.docKeys(source).size,
            stale = models.any { it != model.modelId },
        )
    }

    /** The paths already in the index, for ordering a round. */
    suspend fun indexedPaths(workspaceId: String, dir: String): Set<String> =
        store.docKeys(sourceOf(workspaceId, dir)).toSet()

    /** How many files of this library are in the index - what the settings screen shows. */
    suspend fun indexedFiles(workspaceId: String, dir: String): Int =
        store.docKeys(sourceOf(workspaceId, dir)).size

    companion object {
        const val KIND = "library"

        /** The source key for one library directory of one workspace. */
        fun sourceOf(workspaceId: String, dir: String): String = "$KIND:$workspaceId:$dir"

        /** Every source belonging to a workspace, whatever directory it points at. */
        fun workspacePrefix(workspaceId: String): String = "$KIND:$workspaceId:"
    }
}
