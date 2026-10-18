package me.rerere.rikkahub.data.mission

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [MissionDao] for JVM unit tests — no Room, no instrumentation.
 *
 * Mirrors the real DAO's semantics (upsert = insert-or-replace,
 * `findActiveByConversation` filters terminal statuses) so [MissionRepository]'s
 * state-machine serialisation and cascade-delete logic can be unit-tested fast on the JVM.
 *
 * Event storage has moved to
 * [me.rerere.rikkahub.data.runevent.FakeRunEventDao]; this fake now covers mission
 * rows only.
 *
 * Backed by a [ConcurrentHashMap] so concurrent writes from the repository's
 * coroutine Mutex don't ConcurrentModificationException — the repository's own
 * Mutex is what's actually under test.
 */
class FakeMissionDao : MissionDao {

    private val missions = ConcurrentHashMap<String, MissionEntity>()

    private val missionsFlow = MutableStateFlow<List<MissionEntity>>(emptyList())

    private fun publishMissions() {
        missionsFlow.value = missions.values.sortedByDescending { it.updatedAt }
    }

    fun snapshotMissions(): List<MissionEntity> =
        missions.values.sortedByDescending { it.updatedAt }

    override suspend fun upsert(m: MissionEntity) {
        missions[m.id] = m
        publishMissions()
    }

    override fun observeMission(id: String): Flow<MissionEntity?> =
        missionsFlow.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getById(id: String): MissionEntity? = missions[id]

    override fun observeAll(): Flow<List<MissionEntity>> = missionsFlow

    override suspend fun findActiveByConversation(cid: String): MissionEntity? =
        missions.values
            .filter {
                it.conversationId == cid &&
                    it.statusEnum !in setOf(MissionStatus.DONE, MissionStatus.ARCHIVED)
            }
            .maxByOrNull { it.updatedAt }

    override suspend fun deleteById(id: String) {
        missions.remove(id)
        publishMissions()
    }
}
