package me.rerere.rikkahub.data.vector

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface VectorChunkDao {

    /** Fingerprints of one document, enough to decide whether anything needs re-embedding. */
    @Query(
        "SELECT id, chunk_index, content_hash FROM vector_chunks " +
            "WHERE source = :source AND doc_key = :docKey ORDER BY chunk_index"
    )
    suspend fun fingerprints(source: String, docKey: String): List<StoredChunkRow>

    /** Every chunk of one source under the active model: the candidate set for a brute-force scan. */
    @Query("SELECT * FROM vector_chunks WHERE source = :source AND model_id = :modelId")
    suspend fun candidates(source: String, modelId: String): List<VectorChunkEntity>

    /** One embedding by row id - how an unchanged chunk's vector is carried across a re-index. */
    @Query("SELECT embedding FROM vector_chunks WHERE id = :id")
    suspend fun embeddingOf(id: Long): ByteArray?

    @Insert
    suspend fun insert(rows: List<VectorChunkEntity>)

    @Query("DELETE FROM vector_chunks WHERE source = :source AND doc_key = :docKey")
    suspend fun deleteDoc(source: String, docKey: String)

    @Query("DELETE FROM vector_chunks WHERE source = :source")
    suspend fun deleteSource(source: String)

    @Query("SELECT DISTINCT doc_key FROM vector_chunks WHERE source = :source ORDER BY doc_key")
    suspend fun docKeys(source: String): List<String>

    @Query("SELECT DISTINCT model_id FROM vector_chunks WHERE source = :source")
    suspend fun modelIdsOfSource(source: String): List<String>

    /**
     * Every source whose key begins with [prefix].
     *
     * LIKE, not equality, and the caller still filters with `startsWith`: `_` is a single-character
     * wildcard here and directory names are allowed to contain one, so this may over-report. That
     * is the safe direction - over-reporting costs one comparison, under-reporting would leave
     * rows behind - and it keeps the exact matching in Kotlin, where it is testable.
     */
    @Query("SELECT DISTINCT source FROM vector_chunks WHERE source LIKE :prefix || '%'")
    suspend fun sourcesWithPrefix(prefix: String): List<String>
}
