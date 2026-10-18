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
     * Create a new mission in [MissionStatus.ACTIVE] and log a [RunEventType.CREATED]
     * event. Returns the persisted entity.
     */
    suspend fun create(
        title: String,
        description: String = "",
        acceptance: List<String> = emptyList(),
        conversationId: String? = null,
        assistantId: String? = null,
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
                    payloadJson = JsonInstant.encodeToString(
                        buildJsonObject {
                            put("path", path)
                            put("sourceTool", sourceTool)
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
