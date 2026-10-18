package me.rerere.rikkahub.data.agentrun

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * P5 — DAO for `run_checkpoints`. Writes go through [RunCheckpointRepository], which
 * serialises them and enforces the monotonic-step guard.
 */
@Dao
interface RunCheckpointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: RunCheckpointEntity)

    @Query("SELECT * FROM run_checkpoints WHERE run_id = :runId LIMIT 1")
    suspend fun get(runId: String): RunCheckpointEntity?

    @Query("DELETE FROM run_checkpoints WHERE run_id = :runId")
    suspend fun delete(runId: String)

    /** Count a resume attempt without rewriting the rest of the row. */
    @Query("UPDATE run_checkpoints SET attempts = attempts + 1 WHERE run_id = :runId")
    suspend fun bumpAttempts(runId: String)

    /** Drop checkpoints older than [beforeMs] — chat rows in particular are transient. */
    @Query("DELETE FROM run_checkpoints WHERE updated_at_ms < :beforeMs")
    suspend fun pruneBefore(beforeMs: Long)

    @Query("SELECT COUNT(*) FROM run_checkpoints")
    suspend fun count(): Int
}
