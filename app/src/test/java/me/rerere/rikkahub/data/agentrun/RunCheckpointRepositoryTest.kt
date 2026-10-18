package me.rerere.rikkahub.data.agentrun

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5 — checkpoint writer behaviour, on a fake DAO (no Room, no Android).
 *
 * The invariants that matter for recovery: the row always holds the newest step, a stale
 * write cannot roll it back, resume attempts survive a rewrite, and chat rows age out.
 */
class RunCheckpointRepositoryTest {

    private class FakeRunCheckpointDao : RunCheckpointDao {
        val rows = linkedMapOf<String, RunCheckpointEntity>()

        override suspend fun upsert(row: RunCheckpointEntity) {
            rows[row.runId] = row
        }

        override suspend fun get(runId: String): RunCheckpointEntity? = rows[runId]

        override suspend fun delete(runId: String) {
            rows.remove(runId)
        }

        override suspend fun bumpAttempts(runId: String) {
            rows[runId]?.let { rows[runId] = it.copy(attempts = it.attempts + 1) }
        }

        override suspend fun pruneBefore(beforeMs: Long) {
            rows.entries.removeIf { it.value.updatedAtMs < beforeMs }
        }

        override suspend fun count(): Int = rows.size
    }

    private fun newRepo(dao: FakeRunCheckpointDao = FakeRunCheckpointDao()) =
        dao to RunCheckpointRepository(dao)

    @Test
    fun `record writes every coordinate the recovery path needs`() = runBlocking {
        val (dao, repo) = newRepo()
        val row = repo.record(
            runId = "run-1",
            conversationId = "conv-1",
            missionId = "mission-1",
            kind = "cron",
            phase = "EXECUTING_TOOL",
            stepIndex = 3,
            messageCount = 12,
            pendingToolCalls = listOf("call-a", "call-b"),
        )
        assertEquals("run-1", row?.runId)
        assertEquals("conv-1", dao.rows["run-1"]?.conversationId)
        assertEquals("mission-1", dao.rows["run-1"]?.missionId)
        assertEquals("EXECUTING_TOOL", dao.rows["run-1"]?.phase)
        assertEquals(3, dao.rows["run-1"]?.stepIndex)
        assertEquals(12, dao.rows["run-1"]?.messageCount)
        assertEquals("[\"call-a\",\"call-b\"]", dao.rows["run-1"]?.pendingTools)
        assertEquals(0, dao.rows["run-1"]?.attempts)
    }

    @Test
    fun `a stale write cannot roll the step back`() = runBlocking {
        val (dao, repo) = newRepo()
        repo.record("run-1", "conv-1", null, "cron", "GENERATING", stepIndex = 5, messageCount = 20)
        repo.record("run-1", "conv-1", null, "cron", "GENERATING", stepIndex = 3, messageCount = 18)
        assertEquals(5, dao.rows["run-1"]?.stepIndex)
        assertEquals(20, dao.rows["run-1"]?.messageCount)
    }

    @Test
    fun `repeated step refreshes liveness without losing attempts`() = runBlocking {
        val (dao, repo) = newRepo()
        repo.record("run-1", "conv-1", null, "cron", "GENERATING", stepIndex = 2, messageCount = 8)
        repo.bumpAttempts("run-1")
        repo.record("run-1", "conv-1", null, "cron", "GENERATING", stepIndex = 2, messageCount = 8)
        assertEquals(1, dao.rows["run-1"]?.attempts)
    }

    @Test
    fun `bumpAttempts increments and clear removes the row`() = runBlocking {
        val (dao, repo) = newRepo()
        repo.record("run-1", "conv-1", null, "cron", "GENERATING", stepIndex = 1, messageCount = 4)
        repo.bumpAttempts("run-1")
        repo.bumpAttempts("run-1")
        assertEquals(2, dao.rows["run-1"]?.attempts)
        repo.clear("run-1")
        assertNull(dao.rows["run-1"])
    }

    @Test
    fun `pruneExpired drops rows older than the retention window`() = runBlocking {
        val (dao, repo) = newRepo()
        dao.upsert(
            RunCheckpointEntity(
                runId = "old",
                kind = "chat",
                phase = "GENERATING",
                stepIndex = 0,
                messageCount = 1,
                updatedAtMs = 1L,
            )
        )
        repo.record("fresh", "conv-2", null, "cron", "GENERATING", stepIndex = 0, messageCount = 1)
        repo.pruneExpired()
        assertNull(dao.rows["old"])
        assertTrue(dao.rows.containsKey("fresh"))
    }

    @Test
    fun `oversized pending tool lists are dropped instead of stored truncated`() = runBlocking {
        val (dao, repo) = newRepo()
        val huge = List(200) { "call-$it-" + "x".repeat(40) }
        repo.record("run-1", "conv-1", null, "cron", "EXECUTING_TOOL", stepIndex = 1, messageCount = 2, pendingToolCalls = huge)
        assertNull(dao.rows["run-1"]?.pendingTools)
    }
}
