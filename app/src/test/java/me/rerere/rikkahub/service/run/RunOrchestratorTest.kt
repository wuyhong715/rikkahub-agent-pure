package me.rerere.rikkahub.service.run

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.FakeRunEventDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * P6 — orchestration invariants: the cap is honoured, every child is submitted with the
 * batch's parent/mission coordinates, and a failing child does not abort its siblings.
 */
class RunOrchestratorTest {

    private class FakeRunService(
        private val failAt: Set<Int> = emptySet(),
        private val delayMs: Long = 40,
    ) : RunService {
        val submitted = mutableListOf<RunRequest>()
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)

        override suspend fun submit(request: RunRequest): RunHandle {
            val index = submitted.size
            submitted += request
            return RunHandle(
                id = "run-$index",
                conversationId = Uuid.random(),
                timeoutMs = 5_000,
            )
        }

        override suspend fun awaitTerminal(handle: RunHandle): RunResult {
            val now = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            try {
                delay(delayMs)
                val index = handle.id.removePrefix("run-").toInt()
                return if (index in failAt) {
                    RunResult(RunStatus.FAILED, handle.conversationId, "boom")
                } else {
                    RunResult(RunStatus.COMPLETED, handle.conversationId)
                }
            } finally {
                active.decrementAndGet()
            }
        }

        override suspend fun cancel(handle: RunHandle) = Unit

        override suspend fun resume(runId: String): RunResult? = null

        override fun events(handle: RunHandle): Flow<List<RunEventEntity>> = flowOf(emptyList())
    }

    private fun request(index: Int) = RunRequest(
        assistantId = Uuid.random(),
        prompt = "task $index",
        title = "task $index",
        kind = AgentRunKind.SubAgent,
    )

    @Test
    fun `the concurrency cap is never exceeded`() = runBlocking {
        val fake = FakeRunService()
        val orchestrator = RunOrchestrator({ fake }, RunEventRepository(FakeRunEventDao()))

        val batch = orchestrator.dispatch(
            requests = List(6) { request(it) },
            maxConcurrency = 2,
        )

        assertEquals(6, batch.requested)
        assertEquals(6, batch.completed)
        assertTrue("peak=${fake.peak.get()}", fake.peak.get() <= 2)
        assertEquals(6, fake.submitted.size)
    }

    @Test
    fun `batch coordinates are stamped on every child`() = runBlocking {
        val fake = FakeRunService()
        val orchestrator = RunOrchestrator({ fake }, RunEventRepository(FakeRunEventDao()))

        orchestrator.dispatch(
            requests = listOf(request(0), request(1)),
            missionId = "mission-7",
            parentRunId = "parent-run",
            maxConcurrency = 2,
        )

        assertTrue(fake.submitted.all { it.missionId == "mission-7" })
        assertTrue(fake.submitted.all { it.parentRunId == "parent-run" })
    }

    @Test
    fun `a failing child does not abort its siblings`() = runBlocking {
        val fake = FakeRunService(failAt = setOf(1))
        val orchestrator = RunOrchestrator({ fake }, RunEventRepository(FakeRunEventDao()))

        val batch = orchestrator.dispatch(
            requests = List(4) { request(it) },
            maxConcurrency = 2,
        )

        assertEquals(4, batch.children.size)
        assertEquals(3, batch.completed)
        assertEquals("boom", batch.children[1].failure)
        assertTrue(!batch.allCompleted)
    }
}
