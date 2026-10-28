package me.rerere.rikkahub.data.mission

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.runevent.FakeRunEventDao
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.service.mission.TriggerFireGate
import me.rerere.rikkahub.workflow.model.TriggerSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [MissionRepository]: event creation on `create`, state-machine
 * enforcement in `transition` (legal vs illegal edges, `completedAt` stamping on DONE,
 * clearing on departure), acceptance-checked persistence, artifact-count auto-increment,
 * event-trim past 500, and cascade delete.
 *
 * Event assertions read from [FakeRunEventDao.snapshot] (the `run_events` journal fake)
 * rather than the mission DAO, since event writes are delegated to
 * [RunEventRepository]. Backed by [FakeMissionDao] + [FakeRunEventDao] — no Room, no
 * instrumentation, runs fast on the JVM.
 */
class MissionRepositoryTest {

    private fun repo(): Triple<FakeMissionDao, FakeRunEventDao, MissionRepository> {
        val dao = FakeMissionDao()
        val runEventDao = FakeRunEventDao()
        return Triple(dao, runEventDao, MissionRepository(dao, RunEventRepository(runEventDao)))
    }

    /** All run_events belonging to [missionId], ordered by creation time. */
    private fun eventsFor(dao: FakeRunEventDao, missionId: String) =
        dao.snapshot().filter { it.missionId == missionId }

    @Test
    fun `create persists an ACTIVE mission and logs a CREATED event`() = runBlocking {
        val (dao, runEventDao, repo) = repo()
        val mission = repo.create(
            title = "Ship feature X",
            description = "Deliver the workbench mission module",
            acceptance = listOf("Tests pass", "Contract reviewed"),
            conversationId = "conv-1",
            assistantId = "asst-1",
        )

        // Entity persisted with ACTIVE status and all fields carried through.
        val row = dao.getById(mission.id)
        assertNotNull(row)
        assertEquals("Ship feature X", row!!.title)
        assertEquals(MissionStatus.ACTIVE.name, row.status)
        assertEquals("conv-1", row.conversationId)
        assertEquals(0, row.artifactCount)
        assertNull("completedAt should be null on creation", row.completedAt)
        assertTrue("acceptance JSON should encode the criteria", row.acceptanceCriteria.size == 2)

        // A CREATED event was logged in the run_events journal.
        val events = eventsFor(runEventDao, mission.id)
        assertEquals(1, events.size)
        assertEquals(RunEventType.CREATED.name, events[0].type)
        assertTrue(events[0].title.contains("Ship feature X"))
    }

    @Test
    fun `transition DONE to ACTIVE is rejected by the state machine`() = runBlocking {
        val (dao, _, repo) = repo()
        val mission = repo.create(title = "M1")

        // ACTIVE → REVIEW → DONE (both legal).
        assertTrue(repo.transition(mission.id, MissionStatus.REVIEW))
        assertTrue(repo.transition(mission.id, MissionStatus.DONE))
        assertEquals(MissionStatus.DONE.name, dao.getById(mission.id)!!.status)

        // DONE → ACTIVE is illegal; state must stay DONE.
        assertFalse(repo.transition(mission.id, MissionStatus.ACTIVE))
        assertEquals(MissionStatus.DONE.name, dao.getById(mission.id)!!.status)
    }

    @Test
    fun `transition REVIEW to DONE is legal and stamps completedAt`() = runBlocking {
        val (dao, runEventDao, repo) = repo()
        val mission = repo.create(title = "M2")

        repo.transition(mission.id, MissionStatus.REVIEW)
        val before = dao.getById(mission.id)!!.completedAt
        assertNull("completedAt should be null before DONE", before)

        assertTrue(repo.transition(mission.id, MissionStatus.DONE))
        val after = dao.getById(mission.id)!!.completedAt
        assertNotNull("completedAt should be stamped on entering DONE", after)
        assertTrue(after!! > 0)

        // A STATUS_CHANGED event was logged for the transition.
        val statusEvents = eventsFor(runEventDao, mission.id)
            .filter { it.type == RunEventType.STATUS_CHANGED.name }
        assertTrue("at least one STATUS_CHANGED for REVIEW→DONE",
            statusEvents.any { it.title.contains("DONE") })
    }

    @Test
    fun `transition out of DONE clears completedAt`() = runBlocking {
        val (dao, _, repo) = repo()
        val mission = repo.create(title = "M3")
        repo.transition(mission.id, MissionStatus.DONE)
        assertNotNull(dao.getById(mission.id)!!.completedAt)

        // DONE → ARCHIVED is the only legal exit from DONE; completedAt must be cleared.
        assertTrue(repo.transition(mission.id, MissionStatus.ARCHIVED))
        assertNull(
            "completedAt should be null after leaving DONE",
            dao.getById(mission.id)!!.completedAt,
        )
    }

    @Test
    fun `setAcceptanceChecked persists the checked indices`() = runBlocking {
        val (dao, _, repo) = repo()
        val mission = repo.create(title = "M4", acceptance = listOf("A", "B", "C"))

        repo.setAcceptanceChecked(mission.id, listOf(0, 2))
        val row = dao.getById(mission.id)!!
        assertEquals(listOf(0, 2), row.acceptanceChecked)
    }

    @Test
    fun `attachArtifact increments artifactCount and logs an ARTIFACT event`() = runBlocking {
        val (dao, runEventDao, repo) = repo()
        val mission = repo.create(title = "M5")

        repo.attachArtifact(mission.id, "/workspace/out.txt", "write_text_file", "first artifact")
        repo.attachArtifact(mission.id, "/workspace/out2.txt", "workspace_edit_file", null)

        val row = dao.getById(mission.id)!!
        assertEquals(2, row.artifactCount)

        val artifacts = eventsFor(runEventDao, mission.id)
            .filter { it.type == RunEventType.ARTIFACT.name }
        assertEquals(2, artifacts.size)
        assertEquals("/workspace/out.txt", artifacts[0].title)
    }

    @Test
    fun `logEvent trims to 500 when exceeding the cap`() = runBlocking {
        val (_, runEventDao, repo) = repo()
        val mission = repo.create(title = "M6")

        // Insert 550 NOTE events — each emit call trims internally (500 per mission).
        repeat(550) { i ->
            repo.logEvent(mission.id, RunEventType.NOTE, title = "note-$i")
        }

        val events = eventsFor(runEventDao, mission.id)
        assertEquals("events must be capped at 500", 500, events.size)
        // The 50 oldest should have been evicted (note-0 through note-49 gone;
        // the CREATED event from create() also occupies one slot, but it is the
        // very first row so it is evicted before note-0).
        val titles = events.map { it.title }
        assertFalse("oldest events should be trimmed", titles.contains("note-0"))
        assertFalse("note-49 should be trimmed", titles.contains("note-49"))
        assertTrue("note-50 should survive", titles.contains("note-50"))
        assertTrue("newest event should survive", titles.contains("note-549"))
    }

    @Test
    fun `delete cascades — removes mission and all its events`() = runBlocking {
        val (dao, runEventDao, repo) = repo()
        val mission = repo.create(title = "M7")
        repo.logEvent(mission.id, RunEventType.NOTE, title = "n1")
        repo.logEvent(mission.id, RunEventType.MILESTONE, title = "m1")
        repo.attachArtifact(mission.id, "/p", "tool")
        // create() logs CREATED; the two logEvent calls plus the ARTIFACT from attachArtifact = 4.
        assertEquals(4, eventsFor(runEventDao, mission.id).size)

        repo.delete(mission.id)

        assertNull("mission row gone", dao.getById(mission.id))
        assertEquals("events cascade-deleted", 0, eventsFor(runEventDao, mission.id).size)

        // The mission should also disappear from observeAll.
        val all = dao.observeAll().first()
        assertTrue(all.none { it.id == mission.id })
    }

    // ------------------------------------------------------------------
    // Batch A — task runtime: new mission columns, execution state, limits
    // ------------------------------------------------------------------

    @Test
    fun `create defaults the runtime fields — resumeCount 0, idle state, null config, balanced limits`() =
        runBlocking {
            val (dao, _, repo) = repo()
            val mission = repo.create(title = "M-runtime-defaults")

            // The repository-level one-shot read finds the same row the DAO holds.
            val fetched = repo.getById(mission.id)
            assertNotNull("repo.getById should find the mission", fetched)
            assertEquals(mission.id, fetched!!.id)

            val row = dao.getById(mission.id)!!
            assertEquals(0, row.resumeCount)
            assertEquals("idle", row.executionState)
            assertEquals(MissionExecutionState.IDLE, row.executionStateEnum)
            assertNull(row.workspaceId)
            assertNull(row.approvalMode)
            assertNull(row.limitsJson)
            assertNull(row.lastRunId)
            assertEquals(MissionLimits(), row.limits)
            // 批 4 — 默认值上移到标准档（12 轮 / 300 万 token / 60 分钟一轮）。
            assertEquals(12, row.limits.maxResumeRounds)
            assertEquals(2, row.limits.stallRounds)
            assertEquals(3_000_000L, row.limits.tokenBudget)
            assertEquals(60, row.limits.roundTimeoutMinutes)
            assertEquals(10, row.limits.wrapUpMinutes)
        }

    @Test
    fun `bumpResumeCount returns the incremented value and resetResumeCount zeroes it`() = runBlocking {
        val (dao, _, repo) = repo()
        val mission = repo.create(title = "M-resume")

        assertEquals("first bump", 1, repo.bumpResumeCount(mission.id))
        assertEquals("second bump", 2, repo.bumpResumeCount(mission.id))
        assertEquals(2, dao.getById(mission.id)!!.resumeCount)

        repo.resetResumeCount(mission.id)
        assertEquals(0, dao.getById(mission.id)!!.resumeCount)
        assertEquals("bump after reset", 1, repo.bumpResumeCount(mission.id))
    }

    @Test
    fun `updateExecutionState writes state and lastRunId and advances updatedAt`() = runBlocking {
        val (dao, _, repo) = repo()
        val mission = repo.create(title = "M-exec-state")
        val before = dao.getById(mission.id)!!

        Thread.sleep(10) // let the wall clock move past the create() millisecond
        repo.updateExecutionState(mission.id, MissionExecutionState.RUNNING, lastRunId = "run-abc")

        val running = dao.getById(mission.id)!!
        assertEquals(MissionExecutionState.RUNNING.name, running.executionState)
        assertEquals(MissionExecutionState.RUNNING, running.executionStateEnum)
        assertEquals("run-abc", running.lastRunId)
        assertTrue("updatedAt must advance", running.updatedAt > before.updatedAt)

        // lastRunId = null keeps the stored run id (posture-only updates don't clear it).
        Thread.sleep(10)
        repo.updateExecutionState(mission.id, MissionExecutionState.IDLE)
        val idle = dao.getById(mission.id)!!
        assertEquals(MissionExecutionState.IDLE, idle.executionStateEnum)
        assertEquals("run-abc", idle.lastRunId)
        assertTrue("updatedAt must advance again", idle.updatedAt > running.updatedAt)
    }

    @Test
    fun `limits decoding falls back to defaults for null and malformed JSON, parses valid JSON`() =
        runBlocking {
            val (dao, _, repo) = repo()
            val mission = repo.create(title = "M-limits-decode")
            val base = dao.getById(mission.id)!!

            // Absent (null) column → balanced defaults.
            assertNull(base.limitsJson)
            assertEquals(MissionLimits(), base.limits)

            // Malformed JSON → decode failure is swallowed, defaults returned.
            dao.upsert(base.copy(limitsJson = "{not-json"))
            assertEquals(MissionLimits(), dao.getById(mission.id)!!.limits)

            // Valid JSON → exact values (wire format pinned by a literal).
            dao.upsert(
                base.copy(limitsJson = """{"maxResumeRounds":9,"stallRounds":3,"tokenBudget":123456}""")
            )
            val decoded = dao.getById(mission.id)!!.limits
            assertEquals(9, decoded.maxResumeRounds)
            assertEquals(3, decoded.stallRounds)
            assertEquals(123_456L, decoded.tokenBudget)
        }

    @Test
    fun `updateLimits stores a per-mission override and null clears back to defaults`() = runBlocking {
        val (dao, _, repo) = repo()
        val mission = repo.create(title = "M-limits-update")

        val custom = MissionLimits(maxResumeRounds = 2, stallRounds = 1, tokenBudget = 10_000L)
        repo.updateLimits(mission.id, custom)

        val stored = dao.getById(mission.id)!!
        assertNotNull("override must be persisted as JSON", stored.limitsJson)
        assertEquals(custom, stored.limits)

        repo.updateLimits(mission.id, null)
        val cleared = dao.getById(mission.id)!!
        assertNull(cleared.limitsJson)
        assertEquals(MissionLimits(), cleared.limits)
    }

    @Test
    fun `updateConversation updateApprovalMode and updateWorkspaceAndAssistant persist and bump updatedAt`() =
        runBlocking {
            val (dao, _, repo) = repo()
            val mission = repo.create(title = "M-config")
            Thread.sleep(10)

            repo.updateConversation(mission.id, "conv-42")
            repo.updateApprovalMode(mission.id, "SEMI")
            repo.updateWorkspaceAndAssistant(mission.id, "ws-1", "asst-9")

            val row = dao.getById(mission.id)!!
            assertEquals("conv-42", row.conversationId)
            assertEquals("SEMI", row.approvalMode)
            assertEquals("ws-1", row.workspaceId)
            assertEquals("asst-9", row.assistantId)
            assertTrue("writes must bump updatedAt", row.updatedAt > mission.updatedAt)
        }

    @Test
    fun `updates against a missing mission id are safe no-ops`() = runBlocking {
        val (_, _, repo) = repo()
        assertNull(repo.getById("no-such-mission"))
        repo.updateConversation("no-such-mission", "c-1")
        repo.updateExecutionState("no-such-mission", MissionExecutionState.RUNNING, "run-x")
        assertEquals(0, repo.bumpResumeCount("no-such-mission"))
        repo.resetResumeCount("no-such-mission")
        repo.updateLimits("no-such-mission", MissionLimits(maxResumeRounds = 1))
        repo.updateApprovalMode("no-such-mission", "AUTO")
        repo.updateWorkspaceAndAssistant("no-such-mission", "ws", "asst")
        // Reaching the end without throwing is the assertion.
    }

    // ------------------------------------------------------------------
    // Batch B — start modes: trigger spec, the atomic fire gate, counters
    // ------------------------------------------------------------------

    private val powerTrigger = """{"type":"power_connected"}"""

    @Test
    fun `create persists the trigger spec and its guard rails`() = runBlocking {
        val (dao, _, repo) = repo()
        val mission = repo.create(
            title = "M-trigger-create",
            triggerJson = powerTrigger,
            cooldownSeconds = 120,
            maxRunsPerDay = 3,
        )

        val fetched = repo.getById(mission.id)
        assertNotNull(fetched)
        assertEquals(powerTrigger, fetched!!.triggerJson)
        assertTrue("a new trigger is armed by default", fetched.triggerEnabled)
        assertEquals(120, fetched.cooldownSeconds)
        assertEquals(3, fetched.maxRunsPerDay)
        assertEquals(TriggerSpec.PowerConnected, fetched.triggerSpec)

        // The DAO row agrees — this is what consumeTriggerFire reads back.
        val row = dao.getById(mission.id)!!
        assertEquals(powerTrigger, row.triggerJson)
        assertTrue(row.triggerEnabled)
        assertEquals(120, row.cooldownSeconds)
        assertEquals(3, row.maxRunsPerDay)
    }

    @Test
    fun `consumeTriggerFire fires ACTIVE idle and starts a fresh occurrence`() = runBlocking {
        val (dao, _, repo) = repo()
        val mission = repo.create(title = "M-fire", triggerJson = powerTrigger)
        val day = "2026-10-20"
        val now = 1_000_000L

        // Burn two resume rounds so the reset below is observable.
        repo.bumpResumeCount(mission.id)
        repo.bumpResumeCount(mission.id)
        assertEquals(2, dao.getById(mission.id)!!.resumeCount)

        assertEquals(TriggerFireGate.FIRED, repo.consumeTriggerFire(mission.id, now, day))
        val first = dao.getById(mission.id)!!
        assertEquals(now, first.lastFiredAt)
        assertEquals(1, first.firesToday)
        assertEquals(day, first.firesDay)
        assertEquals("a new occurrence gets a fresh auto-continue budget", 0, first.resumeCount)

        // Same day, no cooldown and no cap → a second occurrence is allowed.
        assertEquals(TriggerFireGate.FIRED, repo.consumeTriggerFire(mission.id, now + 1_000L, day))
        val second = dao.getById(mission.id)!!
        assertEquals(2, second.firesToday)
        assertEquals(now + 1_000L, second.lastFiredAt)
        assertEquals(day, second.firesDay)
    }

    @Test
    fun `cooldown skips a second fire inside the window without burning a slot`() = runBlocking {
        val (dao, _, repo) = repo()
        val mission = repo.create(
            title = "M-cooldown",
            triggerJson = powerTrigger,
            cooldownSeconds = 60,
        )
        val day = "2026-10-20"
        val t0 = 10_000_000L

        assertEquals(TriggerFireGate.FIRED, repo.consumeTriggerFire(mission.id, t0, day))
        assertEquals(
            TriggerFireGate.SKIPPED_COOLDOWN,
            repo.consumeTriggerFire(mission.id, t0 + 30_000L, day),
        )
        val skipped = dao.getById(mission.id)!!
        assertEquals("a skipped fire must not consume the daily slot", 1, skipped.firesToday)
        assertEquals("a skipped fire must not move lastFiredAt", t0, skipped.lastFiredAt)

        // The window is [lastFiredAt, lastFiredAt + 60s): the boundary itself fires again.
        assertEquals(TriggerFireGate.FIRED, repo.consumeTriggerFire(mission.id, t0 + 60_000L, day))
        assertEquals(2, dao.getById(mission.id)!!.firesToday)
    }

    @Test
    fun `daily cap stops further fires and resets on the next day`() = runBlocking {
        val (dao, _, repo) = repo()
        val mission = repo.create(
            title = "M-cap",
            triggerJson = powerTrigger,
            maxRunsPerDay = 1,
        )
        val day1 = "2026-10-20"
        val day2 = "2026-10-21"

        assertEquals(TriggerFireGate.FIRED, repo.consumeTriggerFire(mission.id, 1_000L, day1))
        assertEquals(
            TriggerFireGate.SKIPPED_DAILY_CAP,
            repo.consumeTriggerFire(mission.id, 2_000L, day1),
        )
        assertEquals(1, dao.getById(mission.id)!!.firesToday)

        // A different local day zeroes the stored counter: the mission fires once more.
        assertEquals(TriggerFireGate.FIRED, repo.consumeTriggerFire(mission.id, 3_000L, day2))
        val nextDay = dao.getById(mission.id)!!
        assertEquals(1, nextDay.firesToday)
        assertEquals(day2, nextDay.firesDay)
        assertEquals(3_000L, nextDay.lastFiredAt)
    }

    @Test
    fun `the fire gate skips a mission whose round is still running`() = runBlocking {
        val (dao, _, repo) = repo()
        val mission = repo.create(title = "M-busy", triggerJson = powerTrigger)
        repo.updateExecutionState(mission.id, MissionExecutionState.RUNNING)
        assertEquals(
            "sanity: the repository stores the enum name",
            MissionExecutionState.RUNNING.name,
            dao.getById(mission.id)!!.executionState,
        )

        assertEquals(
            "a round in flight must never start a second one",
            TriggerFireGate.SKIPPED_BUSY,
            repo.consumeTriggerFire(mission.id, 5_000_000L, "2026-10-20"),
        )
        assertEquals("a busy mission must not burn a slot", 0, dao.getById(mission.id)!!.firesToday)
    }

    @Test
    fun `the fire gate skips a done mission`() = runBlocking {
        val (_, _, repo) = repo()
        val mission = repo.create(title = "M-done", triggerJson = powerTrigger)
        assertTrue(repo.transition(mission.id, MissionStatus.DONE))

        assertEquals(
            "finished work is not resurrected by a stale scheduler binding",
            TriggerFireGate.SKIPPED_NOT_ACTIVE,
            repo.consumeTriggerFire(mission.id, 5_000_000L, "2026-10-20"),
        )
    }

    @Test
    fun `the fire gate skips a paused trigger`() = runBlocking {
        val (dao, _, repo) = repo()
        val mission = repo.create(title = "M-paused", triggerJson = powerTrigger)
        repo.setTriggerEnabled(mission.id, false)

        assertEquals(
            TriggerFireGate.SKIPPED_DISABLED,
            repo.consumeTriggerFire(mission.id, 5_000_000L, "2026-10-20"),
        )
        assertEquals(0, dao.getById(mission.id)!!.firesToday)
    }

    @Test
    fun `updateTrigger replaces the schedule and clears the fire counters`() = runBlocking {
        val (dao, _, repo) = repo()
        val mission = repo.create(title = "M-retrigger", triggerJson = powerTrigger)
        assertEquals(TriggerFireGate.FIRED, repo.consumeTriggerFire(mission.id, 1_000L, "2026-10-20"))
        repo.updateNextRunAt(mission.id, 9_999_999L)
        assertNotNull(dao.getById(mission.id)!!.lastFiredAt)

        val nextTrigger = """{"type":"wifi_connected","ssid":"Home"}"""
        repo.updateTrigger(
            mission.id,
            triggerJson = nextTrigger,
            cooldownSeconds = 30,
            maxRunsPerDay = 5,
            enabled = false,
        )

        val row = dao.getById(mission.id)!!
        assertEquals(nextTrigger, row.triggerJson)
        assertEquals(30, row.cooldownSeconds)
        assertEquals(5, row.maxRunsPerDay)
        assertFalse(row.triggerEnabled)
        assertNull("a new schedule invalidates the fire stamp", row.lastFiredAt)
        assertEquals("a new schedule starts the daily counter over", 0, row.firesToday)
        assertNull(row.firesDay)
        assertNull(row.nextRunAt)
    }
}
