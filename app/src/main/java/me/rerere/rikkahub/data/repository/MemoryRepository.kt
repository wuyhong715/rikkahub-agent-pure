package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.runtime.tools.InjectableMemory
import me.rerere.rikkahub.runtime.tools.MemoryInjectionRules
import me.rerere.rikkahub.runtime.tools.MemoryKind

/**
 * What this turn should inject, and how many live records did not fit.
 *
 * [omitted] reaches the prompt so the model knows the bank is bigger than what it can see; a
 * model that assumes it sees everything answers "you never mentioned that" with confidence
 * instead of asking.
 */
data class MemoryInjection(
    val memories: List<AssistantMemory>,
    val omitted: Int,
)

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content, it.kind, it.createdAt, it.validTo) }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content, it.kind, it.createdAt, it.validTo) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content, it.kind, it.createdAt, it.validTo) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { AssistantMemory(it.id, it.content, it.kind, it.createdAt, it.validTo) }
    }

    /**
     * The records that are live *now* — never superseded, or superseded only in the future.
     * Raw rows, for the write gate: it needs the stored vectors as well as the text.
     */
    suspend fun getLiveMemories(assistantId: String, nowMs: Long): List<MemoryEntity> =
        memoryDAO.getLiveMemories(assistantId, nowMs)

    /**
     * The subset of live memory worth re-sending this turn, ordered and budgeted by
     * [MemoryInjectionRules].
     *
     * Everything is still *stored* — this only decides what gets asserted to the model. Expired
     * states and plans, superseded facts and records past the character budget stay visible in
     * the assistant's memory page.
     */
    suspend fun getInjectableMemories(
        assistantId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): MemoryInjection {
        val live = getLiveMemories(assistantId, nowMs)
        if (live.isEmpty()) return MemoryInjection(emptyList(), omitted = 0)

        val selection = MemoryInjectionRules.select(
            memories = live.map { entity ->
                InjectableMemory(
                    id = entity.id,
                    content = entity.content,
                    kind = MemoryKind.fromWire(entity.kind),
                    createdAtMs = entity.createdAt,
                    validToMs = entity.validTo,
                )
            },
            nowMs = nowMs,
        )
        return MemoryInjection(
            memories = selection.memories.map { AssistantMemory(it.id, it.content) },
            omitted = selection.omitted,
        )
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    /**
     * Rewrites one record's text — but only if [ownerId] owns the record.
     *
     * The ownership check is not decoration. Ids are small auto-increment integers, and the
     * model-facing `memory_tool` takes the id straight from the turn: without this, any assistant
     * could send `edit(id=1)` and rewrite (or `delete(id=1)` and erase) another assistant's memory,
     * or the global bank's. "Not yours" is reported the same way as "not found", so the check
     * cannot be used to probe what else exists.
     *
     * The stored vector is dropped with the text: it described the *old* content, and keeping it
     * would make the gate both miss real duplicates and reject unrelated records. The gate
     * re-embeds edited rows opportunistically (see `MemoryWriteGate`), so coverage heals without a
     * sweep.
     */
    suspend fun updateContent(id: Int, content: String, ownerId: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        if (old.assistantId != ownerId) error("Memory record #$id not found")
        val newMemory = old.copy(
            content = content,
            embedding = null,
            embeddingModel = null,
        )
        memoryDAO.updateMemory(newMemory)
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
        )
    }

    /**
     * Stores one record.
     *
     * Deliberately **ungated**: this is the path a human takes (the assistant's memory page) and
     * the gate's own commit step. Model-originated writes go through `MemoryWriteGate` instead.
     */
    suspend fun addMemory(
        assistantId: String,
        content: String,
        kind: MemoryKind = MemoryKind.NOTE,
        createdAt: Long = System.currentTimeMillis(),
        embedding: ByteArray? = null,
        embeddingModel: String? = null,
    ): AssistantMemory {
        val newId = memoryDAO.insertMemory(
            MemoryEntity(
                assistantId = assistantId,
                content = content,
                kind = kind.wire,
                createdAt = createdAt,
                embedding = embedding,
                embeddingModel = embeddingModel,
            )
        ).toInt()
        return AssistantMemory(id = newId, content = content)
    }

    /**
     * Deletes one record — only if [ownerId] owns it. Same reasoning as [updateContent]: the id
     * arrives from the model, so an unscoped delete lets any assistant erase another's memory by
     * counting integers.
     */
    suspend fun deleteMemory(id: Int, ownerId: String) {
        val old = memoryDAO.getMemoryById(id) ?: return
        if (old.assistantId != ownerId) return
        memoryDAO.deleteMemory(id)
    }

    /**
     * Deletes without an ownership check.
     *
     * Only for the human's own surface — the delete button rendered on a memory tool result, where
     * the id belongs to a row the user is looking at. Model-originated paths must use
     * [deleteMemory] with their assistant id; this door exists so the UI does not have to carry an
     * owner it never needed.
     */
    suspend fun deleteMemoryAsUser(id: Int) {
        memoryDAO.deleteMemory(id)
    }
}
