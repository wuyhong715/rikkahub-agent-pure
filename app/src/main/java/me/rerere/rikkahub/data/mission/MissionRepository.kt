package me.rerere.rikkahub.data.mission

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.runtime.util.JsonInstant
import me.rerere.rikkahub.service.mission.MissionTriggerGate
import me.rerere.rikkahub.service.mission.TriggerFireGate
import kotlin.uuid.Uuid

/**
 * Workbench Mission — single shared writer/reader for the mission table.
 *
 * All mutating methods serialise through a [Mutex] so two concurrent transitions on the
 * same mission can't last-writer-win each other's status field — each transition is
 * read-modify-write under the lock.
 *
 * Event writes (CREATED, STATUS_CHANGED, ARTIFACT, …) are delegated to
 * [runEventRepository], which owns the `run_events` journal — the unified, append-only
 * event stream. [logEvent] and [attachArtifact] are best-effort: the journal's
 * [RunEventRepository.emit] swallows failures internally, so a write failure here must
 * never break the mission it is tracking. [transition] validates the state machine and
 * returns `false` for illegal transitions without throwing.
 */
class MissionRepository(
    private val dao: MissionDao,
    private val runEventRepository: RunEventRepository,
) {

    private val writeMutex = Mutex()

    fun observeAll(): Flow<List<MissionEntity>> = dao.observeAll()

    fun observeMission(id: String): Flow<MissionEntity?> = dao.observeMission(id)

    fun observeEvents(missionId: String): Flow<List<RunEventEntity>> =
        runEventRepository.observeByMission(missionId)

    /**
     * One-shot read of the mission row — null when the mission does not exist.
     * [observeMission] remains the Flow reader for UI; launcher / supervisor paths need
     * a single current row without collecting a stream.
     */
    suspend fun getById(id: String): MissionEntity? = dao.getById(id)

    /**
     * Create a new mission in [MissionStatus.ACTIVE] and log a [RunEventType.CREATED]
     * event. Returns the persisted entity.
     */
    suspend fun create(
        title: String,
        description: String = "",
        acceptance: List<String> = emptyList(),
        conversationId: String? = null,
        assistantId: String? = null,
        triggerJson: String? = null,
        cooldownSeconds: Int = 0,
        maxRunsPerDay: Int? = null,
        workflowPath: String? = null,
    ): MissionEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val entity = MissionEntity(
            id = id,
            title = title,
            description = description,
            status = MissionStatus.ACTIVE.name,
            acceptanceJson = JsonInstant.encodeToString(acceptance),
            acceptanceCheckedJson = "[]",
            conversationId = conversationId,
            assistantId = assistantId,
            artifactCount = 0,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            triggerJson = triggerJson,
            cooldownSeconds = cooldownSeconds.coerceAtLeast(0),
            maxRunsPerDay = maxRunsPerDay?.coerceAtLeast(1),
            workflowPath = workflowPath?.takeIf { it.isNotBlank() },
        )
        writeMutex.withLock {
            dao.upsert(entity)
        }
        logEvent(id, RunEventType.CREATED, title = "Mission created: $title")
        return entity
    }

    /**
     * Transition a mission to [to]. Returns `true` if the transition was legal and applied,
     * `false` if the state machine ([MissionStatus.canTransitionTo]) rejects it or the
     * mission does not exist. On success logs a [RunEventType.STATUS_CHANGED] event.
     *
     * Entering [MissionStatus.DONE] stamps [MissionEntity.completedAt]; leaving
     * [MissionStatus.DONE] clears it.
     */
    suspend fun transition(id: String, to: MissionStatus, note: String? = null): Boolean {
        var result = false
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            val current = existing.statusEnum
            if (!current.canTransitionTo(to)) return@withLock
            val now = System.currentTimeMillis()
            val completedAt = when {
                to == MissionStatus.DONE -> now
                current == MissionStatus.DONE -> null
                else -> existing.completedAt
            }
            dao.upsert(
                existing.copy(
                    status = to.name,
                    updatedAt = now,
                    completedAt = completedAt,
                )
            )
            logEvent(
                missionId = id,
                type = RunEventType.STATUS_CHANGED,
                title = "${current.name} -> ${to.name}",
                detail = note,
            )
            result = true
        }
        return result
    }

    /**
     * Update the acceptance criteria checked-state for a mission. The [checked] list holds
     * indices of criteria that are satisfied; stored as JSON in
     * [MissionEntity.acceptanceCheckedJson].
     */
    suspend fun setAcceptanceChecked(id: String, checked: List<Int>) {
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            dao.upsert(
                existing.copy(
                    acceptanceCheckedJson = JsonInstant.encodeToString(checked),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // Batch A — mission runtime field updates (execution posture / circuit breaker / config).
    // Each one is a read-modify-write under [writeMutex], stamps [MissionEntity.updatedAt],
    // and is a safe no-op when the mission no longer exists.
    // ---------------------------------------------------------------------------------

    /**
     * Persist the execution conversation id — written back after a launch creates (or
     * reuses) the conversation the mission runs in.
     */
    suspend fun updateConversation(id: String, conversationId: String?) {
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            dao.upsert(
                existing.copy(
                    conversationId = conversationId,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Update the runtime execution posture ([MissionExecutionState], stored by name) and,
     * when non-null, [lastRunId]. A null [lastRunId] leaves the stored run id untouched —
     * posture-only updates (e.g. RUNNING -> IDLE after a run ends) must not drop the
     * reference to the latest run. Safe no-op when the mission does not exist.
     */
    suspend fun updateExecutionState(
        id: String,
        state: MissionExecutionState,
        lastRunId: String? = null,
    ) {
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            dao.upsert(
                existing.copy(
                    executionState = state.name,
                    lastRunId = lastRunId ?: existing.lastRunId,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Increment [MissionEntity.resumeCount] and return the value after the increment —
     * the auto-continue round the supervisor is about to start. Returns 0 when the
     * mission does not exist (nothing was persisted).
     */
    suspend fun bumpResumeCount(id: String): Int {
        var newValue = 0
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            newValue = existing.resumeCount + 1
            dao.upsert(
                existing.copy(
                    resumeCount = newValue,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
        return newValue
    }

    /**
     * Reset [MissionEntity.resumeCount] to zero — the user took over / restarted the
     * mission manually, so the auto-continue budget starts fresh.
     */
    suspend fun resetResumeCount(id: String) {
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            dao.upsert(
                existing.copy(
                    resumeCount = 0,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Store the per-mission runtime limits as [MissionEntity.limitsJson]; null clears the
     * override so [MissionEntity.limits] falls back to the balanced defaults.
     */
    suspend fun updateLimits(id: String, limits: MissionLimits?) {
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            dao.upsert(
                existing.copy(
                    limitsJson = limits?.sanitized()?.let { JsonInstant.encodeToString(it) },
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Store the per-mission approval posture ([MissionEntity.approvalMode]); null means
     * "inherit the global setting".
     */
    suspend fun updateApprovalMode(id: String, mode: String?) {
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            dao.upsert(
                existing.copy(
                    approvalMode = mode,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Bind (or rebind) the execution workspace and assistant in one write — the create
     * sheet pairs them, and callers should not interleave two partial updates.
     */
    suspend fun updateWorkspaceAndAssistant(id: String, workspaceId: String?, assistantId: String?) {
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            dao.upsert(
                existing.copy(
                    workspaceId = workspaceId,
                    assistantId = assistantId,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * 批 C — point the mission at a workflow document (workspace-relative path), or clear it
     * (`null`) to go back to "let the model choose". Safe no-op when the mission is gone.
     */
    suspend fun updateWorkflowPath(id: String, workflowPath: String?) {
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            dao.upsert(
                existing.copy(
                    workflowPath = workflowPath?.takeIf { it.isNotBlank() },
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * 批 B — replace the mission's start mode (trigger spec + its guard rails). Passing a
     * null [triggerJson] converts the mission back to immediate-only. [enabled] controls
     * whether the scheduler keeps it armed.
     */
    suspend fun updateTrigger(
        id: String,
        triggerJson: String?,
        cooldownSeconds: Int = 0,
        maxRunsPerDay: Int? = null,
        enabled: Boolean = true,
    ) {
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            dao.upsert(
                existing.copy(
                    triggerJson = triggerJson,
                    cooldownSeconds = cooldownSeconds.coerceAtLeast(0),
                    maxRunsPerDay = maxRunsPerDay?.coerceAtLeast(1),
                    triggerEnabled = enabled,
                    // A new schedule invalidates the old counters; start clean.
                    lastFiredAt = null,
                    firesToday = 0,
                    firesDay = null,
                    nextRunAt = null,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Arm / pause the trigger without touching its spec or counters. */
    suspend fun setTriggerEnabled(id: String, enabled: Boolean) {
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            if (existing.triggerEnabled == enabled) return@withLock
            dao.upsert(
                existing.copy(
                    triggerEnabled = enabled,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Store the next expected fire for time-based triggers (best-effort display value). */
    suspend fun updateNextRunAt(id: String, nextRunAtMs: Long?) {
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            if (existing.nextRunAt == nextRunAtMs) return@withLock
            dao.upsert(existing.copy(nextRunAt = nextRunAtMs))
        }
    }

    /**
     * 批 B — the single atomic gate every trigger fire goes through.
     *
     * Under [writeMutex] the current row is re-read and checked in this order: mission exists
     * and is ACTIVE → trigger armed → not already running / waiting for approval → daily cap →
     * cooldown. Only when everything passes are the counters written (`lastFiredAt`,
     * `firesToday`, `firesDay`) and the auto-continue budget reset for the new occurrence.
     *
     * The gate lives here (not only in the scheduler) because two trigger families can fire
     * concurrently: the mutex makes "check + consume" one step, so a burst cannot exceed the
     * daily cap or slip two runs past the cooldown.
     *
     * [day] is the local calendar day (`yyyy-MM-dd`) the caller computed; a counter from a
     * different day is treated as zero.
     */
    suspend fun consumeTriggerFire(id: String, nowMs: Long, day: String): TriggerFireGate {
        var gate: TriggerFireGate = TriggerFireGate.SKIPPED_NOT_ACTIVE
        writeMutex.withLock {
            val existing = dao.getById(id) ?: return@withLock
            val spec = existing.triggerSpec
            val sameDay = existing.firesDay == day
            val firesToday = if (sameDay) existing.firesToday else 0

            gate = MissionTriggerGate.evaluate(
                MissionTriggerGate.Input(
                    status = existing.status,
                    triggerArmed = existing.triggerEnabled && spec != null,
                    executionState = existing.executionState,
                    lastFiredAt = existing.lastFiredAt,
                    firesToday = firesToday,
                    maxRunsPerDay = existing.maxRunsPerDay,
                    cooldownSeconds = existing.cooldownSeconds,
                    nowMs = nowMs,
                )
            )
            if (gate != TriggerFireGate.FIRED) return@withLock

            dao.upsert(
                existing.copy(
                    lastFiredAt = nowMs,
                    firesToday = firesToday + 1,
                    firesDay = day,
                    // A new occurrence gets a fresh auto-continue budget.
                    resumeCount = 0,
                    updatedAt = nowMs,
                )
            )
        }
        return gate
    }

    /**
     * Append a timeline event to a mission. Thin delegate over
     * [RunEventRepository.emit] — best-effort, failures are swallowed inside the journal,
     * never thrown. Retention (500 per mission) is enforced by the journal.
     */
    suspend fun logEvent(
        missionId: String,
        type: RunEventType,
        title: String,
        detail: String? = null,
        payloadJson: String? = null,
        runId: String? = null,
        conversationId: String? = null,
    ) {
        runEventRepository.emit(
            type = type,
            title = title,
            detail = detail,
            payloadJson = payloadJson,
            missionId = missionId,
            conversationId = conversationId,
            runId = runId,
        )
    }

    /**
     * Record an artifact produced by a tool execution. Logs a [RunEventType.ARTIFACT]
     * event, increments [MissionEntity.artifactCount], and bumps
     * [MissionEntity.updatedAt]. Best-effort — failures are swallowed, never thrown.
     */
    suspend fun attachArtifact(
        missionId: String,
        path: String,
        sourceTool: String,
        summary: String? = null,
        change: ArtifactChange? = null,
        /**
         * The run this artifact belongs to. MissionSupervisor reads a round's progress with
         * `observeByRun(runId)`, so an artifact journaled without it counts as nothing; null is
         * only correct when the artifact did not come from a run at all.
         */
        runId: String? = null,
    ) {
        runCatching {
            writeMutex.withLock {
                val existing = dao.getById(missionId) ?: return@withLock
                val now = System.currentTimeMillis()
                dao.upsert(
                    existing.copy(
                        artifactCount = existing.artifactCount + 1,
                        updatedAt = now,
                    )
                )
                logEvent(
                    missionId = missionId,
                    type = RunEventType.ARTIFACT,
                    title = path,
                    detail = summary,
                    runId = runId,
                    payloadJson = JsonInstant.encodeToString(
                        buildJsonObject {
                            put("path", path)
                            put("sourceTool", sourceTool)
                            if (change != null) {
                                put("changeType", change.changeType)
                                put("linesAdded", change.linesAdded)
                                put("linesRemoved", change.linesRemoved)
                                change.diffExcerpt?.let { put("diffExcerpt", it) }
                            }
                        }
                    ),
                )
            }
        }
    }

    /**
     * Delete a mission and all its events. The [ForeignKey] CASCADE on
     * [RunEventEntity] also handles event cleanup at the DB level, but events are
     * deleted explicitly via [RunEventRepository.deleteByMission] for clarity and for
     * the in-memory fake used in JVM tests.
     */
    suspend fun delete(id: String) {
        writeMutex.withLock {
            dao.deleteById(id)
        }
        runEventRepository.deleteByMission(id)
    }

    /** The most recently updated non-terminal mission bound to [cid], or null. */
    suspend fun findActiveByConversation(cid: String): MissionEntity? =
        runCatching { dao.findActiveByConversation(cid) }.getOrNull()
}
