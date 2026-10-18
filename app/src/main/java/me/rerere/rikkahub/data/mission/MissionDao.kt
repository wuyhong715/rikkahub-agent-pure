package me.rerere.rikkahub.data.mission

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the workbench [missions] table.
 *
 * Writes are funnelled through [MissionRepository], which serialises mutations through a
 * Mutex so concurrent state transitions can't last-writer-win each other. The DAO itself
 * stays a thin typed query surface. Mission event writes are handled by
 * [me.rerere.rikkahub.data.runevent.RunEventRepository] /
 * [me.rerere.rikkahub.data.runevent.RunEventDao] — the unified `run_events` journal.
 */
@Dao
interface MissionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MissionEntity)

    @Query("SELECT * FROM missions WHERE id = :id")
    fun observeMission(id: String): Flow<MissionEntity?>

    @Query("SELECT * FROM missions WHERE id = :id")
    suspend fun getById(id: String): MissionEntity?

    @Query("SELECT * FROM missions ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<MissionEntity>>

    @Query(
        "SELECT * FROM missions WHERE conversation_id = :cid " +
            "AND status NOT IN ('DONE','ARCHIVED') " +
            "ORDER BY updated_at DESC LIMIT 1"
    )
    suspend fun findActiveByConversation(cid: String): MissionEntity?

    @Query("DELETE FROM missions WHERE id = :id")
    suspend fun deleteById(id: String)
}
