package me.rerere.rikkahub.data.runevent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [RunEventDao] for JVM unit tests — no Room, no instrumentation.
 *
 * Mirrors the real DAO's semantics (`trimByMission` / `trimByConversation` keep the
 * [KEEP] newest rows of their scope by `created_at`; conversation trim only touches
 * rows with `missionId == null`) so [RunEventRepository]'s serialisation, monotonic
 * clock, and retention logic can be unit-tested fast on the JVM.
 *
 * Backed by [ConcurrentHashMap] so concurrent emits from the repository's coroutine
 * Mutex don't ConcurrentModificationException — the repository's own Mutex is what's
 * actually under test.
 */
class FakeRunEventDao : RunEventDao {

    private val events = ConcurrentHashMap<String, RunEventEntity>()

    private val eventsFlow = MutableStateFlow<List<RunEventEntity>>(emptyList())

    private fun publish() {
        eventsFlow.value = events.values.sortedBy { it.createdAt }
    }

    fun snapshot(): List<RunEventEntity> = events.values.sortedBy { it.createdAt }

    override suspend fun insert(e: RunEventEntity) {
        events[e.id] = e
        publish()
    }

    override fun observeByMission(missionId: String): Flow<List<RunEventEntity>> =
        eventsFlow.map { list -> list.filter { it.missionId == missionId } }

    override fun observeByConversation(conversationId: String): Flow<List<RunEventEntity>> =
        eventsFlow.map { list -> list.filter { it.conversationId == conversationId } }

    override fun observeByRun(runId: String): Flow<List<RunEventEntity>> =
        eventsFlow.map { list -> list.filter { it.runId == runId } }

    override suspend fun deleteByMission(missionId: String) {
        events.entries.removeAll { it.value.missionId == missionId }
        publish()
    }

    override suspend fun trimByMission(missionId: String, keep: Int) {
        val scoped = events.values.filter { it.missionId == missionId }
            .sortedByDescending { it.createdAt }
        val keepIds = scoped.take(keep).map { it.id }.toSet()
        events.entries.removeAll { it.value.missionId == missionId && it.value.id !in keepIds }
        publish()
    }

    override suspend fun trimByConversation(conversationId: String, keep: Int) {
        val scoped = events.values
            .filter { it.conversationId == conversationId && it.missionId == null }
            .sortedByDescending { it.createdAt }
        val keepIds = scoped.take(keep).map { it.id }.toSet()
        events.entries.removeAll {
            it.value.conversationId == conversationId &&
                it.value.missionId == null &&
                it.value.id !in keepIds
        }
        publish()
    }
}
