package me.rerere.rikkahub.data.runevent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the [run_events] journal.
 *
 * Writes are funnelled through [RunEventRepository], which serialises mutations through
 * a Mutex and assigns monotonic timestamps; the DAO stays a thin typed query surface.
 */
@Dao
interface RunEventDao {

    @Insert
    suspend fun insert(e: RunEventEntity)

    /** Timeline source for the workbench mission detail page. */
    @Query("SELECT * FROM run_events WHERE mission_id = :missionId ORDER BY created_at ASC")
    fun observeByMission(missionId: String): Flow<List<RunEventEntity>>

    /** Conversation-scoped view (debugging today; run inspection views later). */
    @Query("SELECT * FROM run_events WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun observeByConversation(conversationId: String): Flow<List<RunEventEntity>>

    /** All events produced by one execution run (sub-agent run id today). */
    @Query("SELECT * FROM run_events WHERE run_id = :runId ORDER BY created_at ASC")
    fun observeByRun(runId: String): Flow<List<RunEventEntity>>

    @Query("DELETE FROM run_events WHERE mission_id = :missionId")
    suspend fun deleteByMission(missionId: String)

    @Query(
        "DELETE FROM run_events WHERE mission_id = :missionId " +
            "AND id NOT IN (SELECT id FROM run_events WHERE mission_id = :missionId " +
            "ORDER BY created_at DESC LIMIT :keep)"
    )
    suspend fun trimByMission(missionId: String, keep: Int = 500)

    @Query(
        "DELETE FROM run_events WHERE conversation_id = :conversationId " +
            "AND mission_id IS NULL " +
            "AND id NOT IN (SELECT id FROM run_events WHERE conversation_id = :conversationId " +
            "AND mission_id IS NULL " +
            "ORDER BY created_at DESC LIMIT :keep)"
    )
    suspend fun trimByConversation(conversationId: String, keep: Int = 500)

    @Query(
        "DELETE FROM run_events WHERE run_id = :runId " +
            "AND mission_id IS NULL AND conversation_id IS NULL " +
            "AND id NOT IN (SELECT id FROM run_events WHERE run_id = :runId " +
            "AND mission_id IS NULL AND conversation_id IS NULL " +
            "ORDER BY created_at DESC LIMIT :keep)"
    )
    suspend fun trimByRun(runId: String, keep: Int = 500)
}
