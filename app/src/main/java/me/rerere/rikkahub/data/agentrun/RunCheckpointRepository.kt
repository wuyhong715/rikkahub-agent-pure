package me.rerere.rikkahub.data.agentrun

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "RunCheckpointRepository"

/**
 * P5 — writer/reader for `run_checkpoints`.
 *
 * [record] is called from the generation loop's per-step hook, so it is on the hot path
 * of every turn: it is serialised by one [Mutex] (checkpoints are tiny and infrequent
 * relative to model calls) and every failure is swallowed — a checkpoint that cannot be
 * written must never break the run it is describing.
 *
 * The monotonic-step guard matters: a slow write from step 5 must not overwrite the row
 * a later step 7 already wrote. Rows therefore only move forward, and a repeated step
 * index still refreshes `updated_at_ms` (the liveness signal boot recovery uses).
 */
class RunCheckpointRepository(private val dao: RunCheckpointDao) {

    private val writeMutex = Mutex()

    /**
     * Persist the latest breakpoint for [runId]. Best-effort; returns the row written
     * (or null when the write failed or was skipped).
     */
    suspend fun record(
        runId: String,
        conversationId: String?,
        missionId: String? = null,
        kind: String,
        phase: String,
        stepIndex: Int,
        messageCount: Int,
        pendingToolCalls: List<String> = emptyList(),
    ): RunCheckpointEntity? {
        val now = System.currentTimeMillis()
        val pendingJson = encodePendingTools(pendingToolCalls)
        var written: RunCheckpointEntity? = null
        runCatching {
            writeMutex.withLock {
                val existing = dao.get(runId)
                if (existing != null && stepIndex < existing.stepIndex) {
                    // Stale write from an older step — refresh liveness only.
                    dao.upsert(existing.copy(updatedAtMs = now))
                    written = existing.copy(updatedAtMs = now)
                    return@withLock
                }
                val row = RunCheckpointEntity(
                    runId = runId,
                    conversationId = conversationId,
                    missionId = missionId,
                    kind = kind,
                    phase = phase,
                    stepIndex = stepIndex,
                    messageCount = messageCount,
                    pendingTools = pendingJson,
                    attempts = existing?.attempts ?: 0,
                    updatedAtMs = now,
                )
                dao.upsert(row)
                written = row
            }
        }.onFailure { error ->
            runCatching { Log.w(TAG, "record($runId) failed", error) }
        }
        return written
    }

    suspend fun get(runId: String): RunCheckpointEntity? =
        runCatching { dao.get(runId) }.getOrNull()

    suspend fun bumpAttempts(runId: String) {
        runCatching { dao.bumpAttempts(runId) }
            .onFailure { runCatching { Log.w(TAG, "bumpAttempts($runId) failed", it) } }
    }

    /** Drop the checkpoint once its run reached a terminal state — nothing to resume. */
    suspend fun clear(runId: String) {
        runCatching { dao.delete(runId) }
            .onFailure { runCatching { Log.w(TAG, "clear($runId) failed", it) } }
    }

    /** Age-based prune; called on boot next to the ledger sweep. */
    suspend fun pruneExpired(nowMs: Long = System.currentTimeMillis()): Int =
        runCatching { dao.pruneBefore(nowMs - RunCheckpointDefaults.RETENTION_MS); dao.count() }
            .getOrDefault(0)

    suspend fun count(): Int = runCatching { dao.count() }.getOrDefault(0)

    private fun encodePendingTools(ids: List<String>): String? {
        if (ids.isEmpty()) return null
        val json = ids.joinToString(prefix = "[", postfix = "]", separator = ",") { id ->
            "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        // Defensive bound: a pathological step can't bloat the row.
        return if (json.length > RunCheckpointDefaults.PENDING_TOOLS_MAX_BYTES) null else json
    }
}
