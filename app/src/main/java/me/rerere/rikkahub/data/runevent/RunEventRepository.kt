package me.rerere.rikkahub.data.runevent

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

private const val TAG = "RunEventRepository"

/**
 * The Run Event Journal — single shared writer for the `run_events` table (P1 of the
 * harness re-architecture).
 *
 * Contract with callers:
 * - **[emit] is best-effort**: failures are logged, never thrown. The journal describes
 *   execution; it must never break execution.
 * - **Monotonic timestamps**: rapid-fire emissions within the same wall-clock
 *   millisecond still order (and trim) deterministically — every event timestamp is at
 *   least one tick after the previous one (same pattern as MissionRepository).
 * - **Retention**: after each insert the affected scope is trimmed — per-mission when
 *   [RunEventEntity.missionId] is set, else per-conversation. Cap: 500 per scope.
 *
 * All mutating calls serialise through [writeMutex].
 */
class RunEventRepository(private val dao: RunEventDao) {

    private val writeMutex = Mutex()

    private val lastEventMs = AtomicLong(0)

    private fun nextEventMs(): Long = lastEventMs.updateAndGet { prev ->
        maxOf(prev + 1, System.currentTimeMillis())
    }

    // ------------------------------------------------------------------
    // Read surface
    // ------------------------------------------------------------------

    /** Timeline source for the workbench mission detail page. */
    fun observeByMission(missionId: String): Flow<List<RunEventEntity>> =
        dao.observeByMission(missionId)

    /** Conversation-scoped view (debugging today; run inspection views later). */
    fun observeByConversation(conversationId: String): Flow<List<RunEventEntity>> =
        dao.observeByConversation(conversationId)

    /** All events produced by one execution run. */
    fun observeByRun(runId: String): Flow<List<RunEventEntity>> =
        dao.observeByRun(runId)

    // ------------------------------------------------------------------
    // Write surface
    // ------------------------------------------------------------------

    /**
     * Append one event to the journal. Best-effort — failures are logged, never thrown.
     *
     * @param type event discriminator, stored as [RunEventType.name].
     * @param title short human-readable label (tool name, status transition, path...).
     * @param detail optional longer text (excerpt, note...).
     * @param payloadJson optional structured JSON payload.
     * @param missionId owning mission, if any.
     * @param conversationId producing conversation, if any.
     * @param runId producing execution run, if known.
     */
    suspend fun emit(
        type: RunEventType,
        title: String,
        detail: String? = null,
        payloadJson: String? = null,
        missionId: String? = null,
        conversationId: String? = null,
        runId: String? = null,
    ) {
        runCatching {
            writeMutex.withLock {
                dao.insert(
                    RunEventEntity(
                        id = Uuid.random().toString(),
                        missionId = missionId,
                        conversationId = conversationId,
                        runId = runId,
                        type = type.name,
                        title = title,
                        detail = detail,
                        payloadJson = payloadJson,
                        createdAt = nextEventMs(),
                    )
                )
                // Retention: trim the scope this event belongs to.
                when {
                    missionId != null -> dao.trimByMission(missionId)
                    conversationId != null -> dao.trimByConversation(conversationId)
                }
            }
        }.onFailure { logSafe { Log.w(TAG, "emit($type, $title) failed", it) } }
    }

    /**
     * Delete all events of a mission. Redundant with the FK CASCADE on
     * [RunEventEntity.missionId] at the DB level, but kept explicit for clarity and for
     * the in-memory fake used in JVM tests.
     */
    suspend fun deleteByMission(missionId: String) {
        runCatching {
            writeMutex.withLock { dao.deleteByMission(missionId) }
        }.onFailure { logSafe { Log.w(TAG, "deleteByMission($missionId) failed", it) } }
    }
}

/**
 * Run a [android.util.Log] call inside a guard so JVM unit tests — where
 * `android.util.Log` is an unmocked stub that throws — don't crash the code under test.
 * Mirrors the `logSafe` helpers in AgentRunRepository / MissionRepository.
 */
private inline fun logSafe(block: () -> Unit) {
    runCatching { block() }
}
