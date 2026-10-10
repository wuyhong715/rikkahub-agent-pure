package me.rerere.rikkahub.data.vector

/**
 * Conversation history as an indexed source.
 *
 * One document per message, keyed `<conversationId>|<messageId>`. The keyword index this replaces
 * (an FTS5 table) made the same choice, for the same reason: history search answers "where did we
 * say this", and an answer that names a conversation but not the message is only half an answer.
 *
 * Unlike the other sources this one is global rather than scoped: history search was never
 * per-assistant, and a user looking for what they discussed before does not care which assistant
 * it was with. There is therefore exactly one source key, and [sync]'s `scope` parameter is what
 * keeps a per-conversation sync from taking the rest of the history with it.
 */
class ConversationVectorSource(
    private val store: VectorIndexStore,
    private val embeddings: EmbeddingService,
) {

    /** One message, already read. */
    data class Doc(val conversationId: String, val messageId: String, val text: String) {
        val docKey: String get() = ConversationSearchRules.docKeyOf(conversationId, messageId)
    }

    data class SyncReport(
        val documents: Int,
        val indexed: Int,
        val unchanged: Int,
        val skipped: Int,
        val chunks: Int,
        val embeddedChunks: Int,
        val reason: String? = null,
    )

    /**
     * Brings the index in line with [docs].
     *
     * [seen] is every message the walk mentioned, read this round or not; a key absent from it is
     * the only evidence of deletion. [scope] narrows which deletions are allowed to happen: null
     * means the walk covered the whole history, while a conversation id means only that
     * conversation's messages may be forgotten - the per-turn sync reads one conversation and must
     * not be able to delete every other one.
     */
    suspend fun sync(
        docs: List<Doc>,
        seen: Set<String>,
        nowMs: Long,
        scope: String? = null,
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

        var indexed = 0
        var unchanged = 0
        var skipped = 0
        var chunks = 0
        var embedded = 0

        for (doc in docs) {
            // No chunker here: one message is one document, and a message long enough to need
            // splitting has already been capped. Splitting it further would put a boundary in the
            // middle of a sentence for no gain - a message is 1-3 chunks as it is.
            val fresh = listOf(
                TextChunk(
                    index = 0,
                    text = doc.text,
                    contentHash = ContentHash.of(doc.text),
                )
            )
            val plan = store.plan(SOURCE, doc.docKey, fresh)
            if (IndexSyncRules.isNoOp(plan)) {
                unchanged++
                continue
            }
            val written = store.apply(SOURCE, doc.docKey, plan, model.modelId, nowMs) { texts ->
                embeddings.embed(texts)
            }
            plan as SyncPlan.Replace
            chunks += written
            embedded += plan.chunks.size - plan.reuse.size
            indexed++
        }

        val indexedKeys = store.docKeys(SOURCE)
        val forget = if (scope == null) {
            ConversationSearchRules.toForget(indexedKeys, seen)
        } else {
            ConversationSearchRules.toForgetIn(scope, indexedKeys, seen)
        }
        forget.forEach { store.forget(SOURCE, it) }

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
     * Semantic search over the whole history.
     *
     * Returns no hits - rather than throwing - when no model is installed. This is the one source
     * where that state is worth shouting about: history search used to work without a model, so
     * "unavailable" here is a feature that stopped working, not a feature that was never set up.
     */
    suspend fun search(
        query: String,
        limit: Int = VectorIndexStore.DEFAULT_LIMIT,
        relativeFloor: Float = VectorSearchRules.DEFAULT_RELATIVE_FLOOR,
    ): ConversationSearchOutcome {
        val model = embeddings.ensureLoaded()
            ?: return ConversationSearchOutcome(available = false)
        val queryVector = embeddings.embedOne(query)
        val result = store.search(SOURCE, model.modelId, queryVector, limit, relativeFloor)
        val models = store.modelIdsOf(SOURCE)
        return ConversationSearchOutcome(
            available = true,
            hits = result.asHits().mapNotNull { chunk ->
                val conversationId = ConversationSearchRules.conversationIdOf(chunk.docKey)
                    ?: return@mapNotNull null
                ConversationSearchHit(
                    conversationId = conversationId,
                    messageId = chunk.docKey.substringAfter('|'),
                    score = chunk.score,
                    text = chunk.text,
                )
            },
            indexedMessages = store.docKeys(SOURCE).size,
            stale = models.any { it != model.modelId },
        )
    }

    /** How many messages are indexed - what the settings row shows. */
    suspend fun indexedMessages(): Int = store.docKeys(SOURCE).size

    companion object {
        const val SOURCE = ConversationSearchRules.SOURCE
    }
}
