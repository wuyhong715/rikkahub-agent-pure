package me.rerere.rikkahub.data.repository

import android.util.Log
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.vector.EmbeddingCodec
import me.rerere.rikkahub.data.vector.EmbeddingService
import me.rerere.rikkahub.runtime.LoopMemory
import me.rerere.rikkahub.runtime.LoopMemoryWrite
import me.rerere.rikkahub.runtime.tools.MemoryKind
import me.rerere.rikkahub.runtime.tools.MemoryRecord
import me.rerere.rikkahub.runtime.tools.MemoryRejectReason
import me.rerere.rikkahub.runtime.tools.MemoryWriteDecision
import me.rerere.rikkahub.runtime.tools.MemoryWriteRules

/**
 * The door every model-originated memory write goes through.
 *
 * ## Why the gate lives here and not in the tool
 *
 * The rules are pure and live in `:runtime` (`MemoryWriteRules`), where they are unit-tested
 * without a database. What they cannot do is *see* the stored records or produce a vector —
 * that needs Room and the embedding model. This class is that half: it loads the live bank,
 * asks the rules, runs the semantic tier when a model is available, and only then writes.
 *
 * ## Two tiers, and why the second is optional
 *
 * Tier 1 (wording) always runs. Tier 2 (cosine at or above `EMBEDDING_DUPLICATE_COSINE`) needs
 * an installed embedding model; when there is none the gate still works, it just misses
 * paraphrases that share no words. Everything about tier 2 is best-effort: an embedder that
 * throws must not stop a memory from being stored, because a lost memory is worse than a
 * duplicate.
 *
 * ## Self-healing coverage
 *
 * Vectors are stored per row, so a row written before the model was installed, or edited since
 * (edit drops the vector), has none. Rather than a full re-index sweep on model change, each
 * write that successfully embeds itself also embeds a small bounded batch of vectorless rows.
 * Coverage climbs during normal use and never blocks the write it rode in on.
 */
class MemoryWriteGate(
    private val repository: MemoryRepository,
    private val memoryDAO: MemoryDAO,
    private val embeddings: EmbeddingService,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        private const val TAG = "MemoryWriteGate"

        /** Vectorless rows filled per successful write. Small enough to stay off the tool path. */
        private const val BACKFILL_BATCH = 8
    }

    /**
     * Stores [content] as a [kind] memory, or explains why it did not.
     *
     * The returned [LoopMemoryWrite.Rejected] is a normal outcome: the caller renders it as a
     * tool result so the model can retry as an `edit` in the same turn.
     */
    suspend fun create(
        assistantId: String,
        content: String,
        kind: MemoryKind,
    ): LoopMemoryWrite {
        val text = content.trim()
        val now = clock()
        val live = repository.getLiveMemories(assistantId, now)
        val existing = live.map { entity ->
            MemoryRecord(
                id = entity.id,
                content = entity.content,
                kind = MemoryKind.fromWire(entity.kind),
            )
        }

        when (val decision = MemoryWriteRules.decide(content, existing)) {
            is MemoryWriteDecision.Reject -> return decision.toRejection()
            MemoryWriteDecision.Accept -> Unit
        }

        // Tier 2 — only when a model is installed AND it produced a vector. Everything in here
        // is best-effort: a slow or failing embedder must never block the write.
        val embedded = runCatching {
            val model = embeddings.ensureLoaded() ?: return@runCatching null
            val vector = embeddings.embedDocuments(listOf(text)).firstOrNull()
                ?: return@runCatching null
            model.fileName to vector
        }.onFailure { error ->
            Log.w(TAG, "create: embedding failed, storing without a vector", error)
        }.getOrNull()

        if (embedded != null) {
            val (modelId, candidate) = embedded
            val stored = live.mapNotNull { entity ->
                val blob = entity.embedding ?: return@mapNotNull null
                // Vectors from two different models are not comparable; a row written under an
                // older model simply does not participate until the backfill re-embeds it.
                if (entity.embeddingModel != modelId) return@mapNotNull null
                MemoryRecord(
                    id = entity.id,
                    content = entity.content,
                    kind = MemoryKind.fromWire(entity.kind),
                ) to EmbeddingCodec.decode(blob)
            }
            MemoryWriteRules.duplicateByEmbedding(candidate, stored)?.let { match ->
                return LoopMemoryWrite.Rejected(
                    reason = MemoryRejectReason.DUPLICATE.wire,
                    hint = MemoryWriteRules.rejectionHint(MemoryRejectReason.DUPLICATE, match),
                    existing = LoopMemory(match.id, match.content),
                )
            }
        }

        val stored = repository.addMemory(
            assistantId = assistantId,
            content = text,
            kind = kind,
            createdAt = now,
            embedding = embedded?.second?.let(EmbeddingCodec::encode),
            embeddingModel = embedded?.first,
        )

        if (embedded != null) {
            backfillEmbeddings(assistantId, embedded.first)
        }

        Log.i(TAG, "create: stored memory #${stored.id} as ${kind.wire} (vector=${embedded != null})")
        return LoopMemoryWrite.Stored(LoopMemory(stored.id, stored.content))
    }

    private fun MemoryWriteDecision.Reject.toRejection(): LoopMemoryWrite.Rejected =
        LoopMemoryWrite.Rejected(
            reason = reason.wire,
            hint = MemoryWriteRules.rejectionHint(reason, existing),
            existing = existing?.let { LoopMemory(it.id, it.content) },
        )

    /**
     * Fills the vectors of at most [BACKFILL_BATCH] vectorless rows under [modelId].
     *
     * Failures are logged and dropped: this runs on the back of a write the user is waiting
     * for, and partial coverage is a smaller problem than a failed memory.
     */
    private suspend fun backfillEmbeddings(assistantId: String, modelId: String) {
        runCatching {
            val rows = memoryDAO.getRowsMissingEmbedding(assistantId, modelId, BACKFILL_BATCH)
            if (rows.isEmpty()) return@runCatching
            val vectors = embeddings.embedDocuments(rows.map { it.content })
            rows.forEachIndexed { index, row ->
                val vector = vectors.getOrNull(index) ?: return@forEachIndexed
                memoryDAO.updateEmbedding(row.id, EmbeddingCodec.encode(vector), modelId)
            }
        }.onFailure { error ->
            Log.w(TAG, "backfill: could not embed up to $BACKFILL_BATCH legacy rows", error)
        }
    }
}
