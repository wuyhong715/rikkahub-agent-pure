package me.rerere.rikkahub.data.mission

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.runevent.FakeRunEventDao
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.data.usage.UsageRecordDao
import me.rerere.rikkahub.data.usage.UsageRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ArtifactsRepository]: the ARTIFACT-event → [Artifact] projection,
 * and the [MissionSettlement] aggregation over mission row + run-event journal + usage
 * ledger.
 *
 * Uses the same in-memory fakes as [MissionRepositoryTest] ([FakeMissionDao] +
 * [FakeRunEventDao]) plus a local [FakeUsageRecordDao]; no Room, no instrumentation.
 */
class ArtifactsRepositoryTest {

    /**
     * In-memory [UsageRecordDao] mirroring the real queries this repository relies on
     * (`forConversation` = filter by conversation, ordered by `created_at_ms`, capped by
     * `limit`).
     */
    private class FakeUsageRecordDao : UsageRecordDao {
        val rows = mutableListOf<UsageRecordEntity>()

        override suspend fun insert(record: UsageRecordEntity) {
            rows += record
        }

        override suspend fun latest(limit: Int) = rows.takeLast(limit).reversed()

        override suspend fun since(sinceMs: Long, limit: Int) =
            rows.filter { it.createdAtMs >= sinceMs }

        override suspend fun count() = rows.size

        override suspend fun forConversation(conversationId: String, limit: Int) =
            rows.filter { it.conversationId == conversationId }
                .sortedBy { it.createdAtMs }
                .take(limit)

        override suspend fun tokensForParentRun(parentRunId: String) = 0L

        override suspend fun tokensSince(sinceMs: Long) = 0L

        override suspend fun deleteOlderThan(cutoffMs: Long) = 0
    }

    /** The wired-up graph under test. */
    private class Fixture {
        val missionDao = FakeMissionDao()
        val runEventDao = FakeRunEventDao()
        val usageDao = FakeUsageRecordDao()
        val missionRepository =
            MissionRepository(missionDao, RunEventRepository(runEventDao))
        val artifactsRepository = ArtifactsRepository(missionRepository, usageDao)
    }

    private fun usageRow(
        id: String,
        conversationId: String?,
        input: Int,
        output: Int,
        costUsd: Double?,
        atMs: Long,
    ) = UsageRecordEntity(
        id = id,
        createdAtMs = atMs,
        purpose = "TOOL_LOOP",
        conversationId = conversationId,
        inputTokens = input,
        outputTokens = output,
        totalTokens = input + output,
        cachedTokens = 0,
        cachedTokensReported = false,
        providerCostUsd = costUsd,
    )

    // ------------------------------------------------------------------
    // observeArtifacts
    // ------------------------------------------------------------------

    @Test
    fun `observeArtifacts projects ARTIFACT events and drops every other event type`() =
        runBlocking {
            val f = Fixture()
            val mission = f.missionRepository.create(title = "M1")

            // Noise: create() already logged CREATED, add a NOTE too.
            f.missionRepository.logEvent(mission.id, RunEventType.NOTE, title = "a note")
            f.missionRepository.attachArtifact(
                missionId = mission.id,
                path = "/workspace/out.txt",
                sourceTool = "write_text_file",
                summary = "created file",
                change = ArtifactChange("created", linesAdded = 3, linesRemoved = 0, diffExcerpt = null),
            )
            f.missionRepository.attachArtifact(
                missionId = mission.id,
                path = "/workspace/out.txt",
                sourceTool = "workspace_edit_file",
                summary = null,
                change = ArtifactChange("modified", linesAdded = 4, linesRemoved = 2, diffExcerpt = "new content"),
            )

            val artifacts = f.artifactsRepository.observeArtifacts(mission.id).first()

            assertEquals("only ARTIFACT events project", 2, artifacts.size)

            val created = artifacts[0]
            assertEquals("/workspace/out.txt", created.path)
            assertEquals("write_text_file", created.sourceTool)
            assertEquals("created", created.changeType)
            assertEquals(3, created.linesAdded)
            assertEquals(0, created.linesRemoved)
            assertNull(created.diffExcerpt)
            assertEquals("created file", created.summary)
            assertEquals(mission.id, created.missionId)
            assertTrue("artifact id is the source event id", created.id.isNotBlank())

            val modified = artifacts[1]
            assertEquals("modified", modified.changeType)
            assertEquals(4, modified.linesAdded)
            assertEquals(2, modified.linesRemoved)
            assertEquals("new content", modified.diffExcerpt)
        }

    @Test
    fun `observeArtifacts is empty for a mission with no ARTIFACT events`() = runBlocking {
        val f = Fixture()
        val mission = f.missionRepository.create(title = "M-no-artifacts")
        f.missionRepository.logEvent(mission.id, RunEventType.NOTE, title = "just a note")

        assertEquals(emptyList<Artifact>(), f.artifactsRepository.observeArtifacts(mission.id).first())
    }

    // ------------------------------------------------------------------
    // observeSettlement
    // ------------------------------------------------------------------

    @Test
    fun `observeSettlement aggregates mission events and conversation usage`() = runBlocking {
        val f = Fixture()
        val mission = f.missionRepository.create(
            title = "M2",
            acceptance = listOf("Tests pass", "Reviewed", "Docs updated"),
            conversationId = "conv-1",
        )
        f.missionRepository.setAcceptanceChecked(mission.id, listOf(0, 1))
        f.missionRepository.logEvent(mission.id, RunEventType.TOOL_RESULT, title = "read_file")
        f.missionRepository.logEvent(mission.id, RunEventType.TOOL_RESULT, title = "write_text_file")
        f.missionRepository.attachArtifact(
            missionId = mission.id,
            path = "/w/a.txt",
            sourceTool = "write_text_file",
            change = ArtifactChange("created", 1, 0, null),
        )

        // Two rows on this conversation, one on another — the latter must not leak in.
        f.usageDao.rows += usageRow("u1", "conv-1", input = 100, output = 20, costUsd = 0.5, atMs = 1)
        f.usageDao.rows += usageRow("u2", "conv-1", input = 30, output = 10, costUsd = 0.25, atMs = 2)
        f.usageDao.rows += usageRow("u3", "other-conv", input = 999, output = 999, costUsd = 99.0, atMs = 3)

        // Pin the timestamps so the active-duration assertion is deterministic.
        val row = f.missionDao.getById(mission.id)!!
        f.missionDao.upsert(row.copy(createdAt = 1_000L, updatedAt = 5_000L))

        val settlement = f.artifactsRepository.observeSettlement(mission.id).first()

        assertEquals("M2", settlement.mission.title)
        assertEquals(2, settlement.toolCallCount)
        assertEquals(1, settlement.artifactCount)
        assertEquals(1, settlement.artifacts.size)
        assertEquals(160L, settlement.totalTokens)
        assertEquals(0.75, settlement.totalCostUsd, 1e-9)
        assertEquals(4_000L, settlement.activeDurationMs)
        assertEquals(3, settlement.acceptanceTotal)
        assertEquals(2, settlement.acceptanceChecked)
        assertEquals(2f / 3f, settlement.acceptanceProgress, 1e-6f)
        assertFalse("2 of 3 checked is not complete", settlement.canComplete)

        val lastEventAt = f.runEventDao.snapshot()
            .filter { it.missionId == mission.id }
            .maxOf { it.createdAt }
        assertEquals(lastEventAt, settlement.lastActivityAt)
    }

    @Test
    fun `observeSettlement reports zero cost and zero tokens when the mission has no conversation`() =
        runBlocking {
            val f = Fixture()
            val mission = f.missionRepository.create(title = "M3", conversationId = null)
            // Ledger rows exist, but none are scoped to this mission — they must not be folded in.
            f.usageDao.rows += usageRow("u1", "conv-elsewhere", 500, 500, 5.0, atMs = 1)

            val settlement = f.artifactsRepository.observeSettlement(mission.id).first()

            assertNull(settlement.mission.conversationId)
            assertEquals(0L, settlement.totalTokens)
            assertEquals(0.0, settlement.totalCostUsd, 1e-9)
            assertEquals(0, settlement.toolCallCount)
            assertEquals(0, settlement.artifactCount)
        }

    @Test
    fun `observeSettlement treats a null provider cost as zero`() = runBlocking {
        val f = Fixture()
        val mission = f.missionRepository.create(title = "M4", conversationId = "conv-2")
        f.usageDao.rows += usageRow("u1", "conv-2", 10, 5, costUsd = null, atMs = 1)
        f.usageDao.rows += usageRow("u2", "conv-2", 10, 5, costUsd = 0.3, atMs = 2)

        val settlement = f.artifactsRepository.observeSettlement(mission.id).first()

        assertEquals(30L, settlement.totalTokens)
        assertEquals(0.3, settlement.totalCostUsd, 1e-9)
    }

    @Test
    fun `observeSettlement emits nothing for an unknown mission`() = runBlocking {
        val f = Fixture()

        val emitted = withTimeoutOrNull(200) {
            f.artifactsRepository.observeSettlement("does-not-exist").first()
        }

        assertNull("mission null → flow stays silent for the VM's null initial value", emitted)
    }

    @Test
    fun `acceptanceProgress is zero and canComplete false with no acceptance criteria`() =
        runBlocking {
            val f = Fixture()
            val mission = f.missionRepository.create(title = "M5")

            val settlement = f.artifactsRepository.observeSettlement(mission.id).first()

            assertEquals(0, settlement.acceptanceTotal)
            assertEquals(0f, settlement.acceptanceProgress, 1e-6f)
            assertFalse(settlement.canComplete)
            assertNotNull("mission row is always carried through", settlement.mission)
        }
}
