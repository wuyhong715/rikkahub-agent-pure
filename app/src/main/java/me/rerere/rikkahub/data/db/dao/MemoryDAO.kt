package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    /**
     * The records that are still true right now: never superseded, or superseded only in the
     * future. Both the write gate (duplicate detection) and the prompt (injection) read this
     * one query, so they can never disagree about what "live" means.
     */
    @Query(
        "SELECT * FROM memoryentity WHERE assistant_id = :assistantId " +
            "AND (valid_to IS NULL OR valid_to > :nowMs) ORDER BY created_at DESC, id DESC"
    )
    suspend fun getLiveMemories(assistantId: String, nowMs: Long): List<MemoryEntity>

    /**
     * Rows the gate should (re-)embed in a bounded pass.
     *
     * "Missing" includes rows carrying a vector from a *different* model: those vectors describe the
     * same text in another space, so they can neither be compared with the current model's queries
     * nor left to rot. Without the model condition a swap froze the whole legacy bank — every row
     * had an embedding, so nothing was ever refilled.
     */
    @Query(
        "SELECT * FROM memoryentity WHERE assistant_id = :assistantId " +
            "AND (embedding IS NULL OR embedding_model IS NULL OR embedding_model != :modelId) " +
            "ORDER BY created_at DESC, id DESC LIMIT :limit"
    )
    suspend fun getRowsMissingEmbedding(
        assistantId: String,
        modelId: String,
        limit: Int,
    ): List<MemoryEntity>

    @Query("UPDATE memoryentity SET embedding = :embedding, embedding_model = :modelId WHERE id = :id")
    suspend fun updateEmbedding(id: Int, embedding: ByteArray, modelId: String)

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
