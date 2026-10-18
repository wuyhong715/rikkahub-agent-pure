package me.rerere.rikkahub.data.runevent

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [RunEventRepository]: the full-coordinate write, the monotonic timestamp
 * clock under rapid fire, per-scope retention (mission vs conversation, and that conversation
 * trim never touches mission-bound rows), explicit [deleteByMission], the best-effort contract
 * (a throwing DAO must not escape [emit]), and the observe filters.
 *
 * Backed by [FakeRunEventDao] — no Room, no instrumentation, runs fast on the JVM. The
 * repository's own [Mutex] and [AtomicLong] clock are what is under test; the fake mirrors the
 * real DAO's trim semantics so the retention paths exercise the same keep-newest-500 logic.
 *
 * Uses `runBlocking` (not `runTest`) to match the rest of this repo's data-layer test style
 * (see [MissionRepositoryTest] / [AgentRunRepositoryTest]); kotlinx-coroutines-test is not on
 * the test classpath.
 */
class RunEventRepositoryTest {

    private fun repo(): Pair<FakeRunEventDao, RunEventRepository> {
        val dao = FakeRunEventDao()
        return dao to RunEventRepository(dao)
    }

    @Test
    fun `emit persists the full event coordinate`() = runBlocking {
        val (dao, repo) = repo()
        repo.emit(
            type = RunEventType.TOOL_RESULT,
            title = "write_text_file",
            detail = "wrote 42 bytes",
            payloadJson = """{"tool":"write_text_file","bytes":42}""",
            missionId = "m1",
            conversationId = "c1",
            runId = "r1",
        )

        val rows = dao.snapshot()
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals(RunEventType.TOOL_RESULT.name, row.type)
        assertEquals("write_text_file", row.title)
        assertEquals("wrote 42 bytes", row.detail)
        assertEquals("""{"tool":"write_text_file","bytes":42}""", row.payloadJson)
        assertEquals("m1", row.missionId)
        assertEquals("c1", row.conversationId)
        assertEquals("r1", row.runId)
        assertTrue("createdAt must be populated", row.createdAt > 0)
    }

    @Test
    fun `rapid emits produce strictly increasing createdAt`() = runBlocking {
        val (dao, repo) = repo()
        // 50 back-to-back emits: even if System.currentTimeMillis() is constant for the whole
        // burst, the monotonic clock (max(prev + 1, now)) must order every event after the
        // previous one so trim and observe stay deterministic.
        repeat(50) { i -> repo.emit(RunEventType.NOTE, title = "n$i", missionId = "m1") }

        val rows = dao.snapshot()
        assertEquals(50, rows.size)
        rows.zipWithNext().forEach { (earlier, later) ->
            assertTrue(
                "createdAt must be strictly increasing (was ${earlier.createdAt} then ${later.createdAt})",
                later.createdAt > earlier.createdAt,
            )
        }
    }

    @Test
    fun `mission scope trims to the 500 newest after exceeding the cap`() = runBlocking {
        val (dao, repo) = repo()
        // 505 NOTE events for one mission — each emit trims that mission's scope to 500.
        repeat(505) { i -> repo.emit(RunEventType.NOTE, title = "note-$i", missionId = "m1") }

        val rows = dao.snapshot().filter { it.missionId == "m1" }
        assertEquals("mission scope must be capped at 500", 500, rows.size)
        val titles = rows.map { it.title }
        // createdAt rises with i, so the 5 oldest (note-0..note-4) are evicted; note-5..note-504
        // are the 500 newest that survive.
        assertFalse("note-0 should be trimmed", titles.contains("note-0"))
        assertFalse("note-4 should be trimmed", titles.contains("note-4"))
        assertTrue("note-5 should survive", titles.contains("note-5"))
        assertTrue("note-504 should survive", titles.contains("note-504"))
    }

    @Test
    fun `conversation trim only evicts mission-less rows`() = runBlocking {
        val (dao, repo) = repo()
        // 5 mission-bound events share conversation c1 but belong to mission m1. A
        // conversation-scope trim only ever touches rows where missionId IS NULL, so these
        // must survive even though they share the trimmed conversation.
        repeat(5) { i ->
            repo.emit(RunEventType.NOTE, title = "mission-$i", missionId = "m1", conversationId = "c1")
        }
        // 505 conversation-only events (missionId null) — trimmed to the 500 newest.
        repeat(505) { i -> repo.emit(RunEventType.NOTE, title = "conv-$i", conversationId = "c1") }

        val all = dao.snapshot()
        assertEquals("5 mission-bound events must survive the conversation trim", 5, all.count { it.missionId == "m1" })
        assertEquals(
            "conversation-only scope must be capped at 500",
            500,
            all.count { it.conversationId == "c1" && it.missionId == null },
        )
        val missionTitles = all.filter { it.missionId == "m1" }.map { it.title }
        assertTrue(
            "all mission-bound events must survive",
            missionTitles.containsAll(listOf("mission-0", "mission-1", "mission-2", "mission-3", "mission-4")),
        )
        val convTitles = all.filter { it.conversationId == "c1" && it.missionId == null }.map { it.title }
        assertFalse("conv-0 should be trimmed", convTitles.contains("conv-0"))
        assertTrue("conv-504 should survive", convTitles.contains("conv-504"))
    }

    @Test
    fun `deleteByMission removes only the named mission rows`() = runBlocking {
        val (dao, repo) = repo()
        repo.emit(RunEventType.NOTE, title = "a", missionId = "m1")
        repo.emit(RunEventType.NOTE, title = "b", missionId = "m1")
        repo.emit(RunEventType.NOTE, title = "c", missionId = "m2")
        repo.emit(RunEventType.NOTE, title = "d", conversationId = "c1") // mission-less

        repo.deleteByMission("m1")

        val remaining = dao.snapshot()
        assertTrue("m1 events should be gone", remaining.none { it.missionId == "m1" })
        assertTrue("m2 event must survive", remaining.any { it.missionId == "m2" && it.title == "c" })
        assertTrue("mission-less event must survive", remaining.any { it.title == "d" })
        assertEquals(2, remaining.size)
    }

    @Test
    fun `emit swallows a DAO insert failure rather than throwing`() = runBlocking {
        // A DAO whose insert always blows up. Reads/trims delegate to a real fake so the object
        // is a complete implementation, but the failure path never reaches trim (insert throws
        // before it), so the delegation is just for compilation.
        val backing = FakeRunEventDao()
        val throwingDao = object : RunEventDao {
            override suspend fun insert(e: RunEventEntity) {
                throw RuntimeException("simulated disk failure")
            }

            override fun observeByMission(missionId: String) = backing.observeByMission(missionId)

            override fun observeByConversation(conversationId: String) = backing.observeByConversation(conversationId)

            override fun observeByRun(runId: String) = backing.observeByRun(runId)

            override suspend fun deleteByMission(missionId: String) = backing.deleteByMission(missionId)

            override suspend fun trimByMission(missionId: String, keep: Int) = backing.trimByMission(missionId, keep)

            override suspend fun trimByConversation(conversationId: String, keep: Int) = backing.trimByConversation(conversationId, keep)
        }
        val repo = RunEventRepository(throwingDao)

        // The journal's contract: emit is best-effort. If it re-threw, this runBlocking body
        // would propagate the exception and the test would error out.
        repo.emit(RunEventType.NOTE, title = "doomed", missionId = "m1")

        // Nothing landed — insert threw before trim, and the failure was swallowed by
        // runCatching + logSafe, leaving no partial write behind.
        assertEquals(0, backing.snapshot().size)
    }

    @Test
    fun `observeByMission and observeByConversation filter by their scope`() = runBlocking {
        val (dao, repo) = repo()
        repo.emit(RunEventType.NOTE, title = "e1", missionId = "m1", conversationId = "c1")
        repo.emit(RunEventType.NOTE, title = "e2", missionId = "m1")
        repo.emit(RunEventType.NOTE, title = "e3", conversationId = "c1")
        repo.emit(RunEventType.NOTE, title = "e4", missionId = "m2")

        val byMission = repo.observeByMission("m1").first()
        assertEquals(2, byMission.size)
        assertTrue(byMission.map { it.title }.containsAll(listOf("e1", "e2")))

        val byConversation = repo.observeByConversation("c1").first()
        assertEquals(2, byConversation.size)
        assertTrue(byConversation.map { it.title }.containsAll(listOf("e1", "e3")))
    }
}
