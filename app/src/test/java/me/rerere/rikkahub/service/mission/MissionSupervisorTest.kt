package me.rerere.rikkahub.service.mission

import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum
import me.rerere.rikkahub.data.ai.tools.buildMissionReportTool
import me.rerere.rikkahub.data.mission.FakeMissionDao
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionExecutionState
import me.rerere.rikkahub.data.mission.MissionLimits
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.runevent.FakeRunEventDao
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.service.run.RunHandle
import me.rerere.rikkahub.service.run.RunRequest
import me.rerere.rikkahub.service.run.RunResult
import me.rerere.rikkahub.service.run.RunService
import me.rerere.rikkahub.runtime.util.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CONV1 = "11111111-1111-1111-1111-111111111111"
private const val CONV2 = "22222222-2222-2222-2222-222222222222"

/**
 * JVM unit tests for [MissionSupervisor] and [buildMissionReportTool].
 *
 * Fake stack: [FakeMissionDao] + [FakeRunEventDao] back a REAL [MissionRepository] +
 * REAL [RunEventRepository] (so signal detection runs against actual journal rows),
 * a [FakeRunService] records every submitted [RunRequest] behind the REAL
 * [MissionLauncher], and lightweight fakes stand in for the two probes. No Room, no
 * Android — everything runs fast on the JVM.
 */
class MissionSupervisorTest {

    // ---------------------------------------------------------------- fixtures

    private class FakeRunService : RunService {
        val submitted = mutableListOf<RunRequest>()
        val handles = mutableListOf<RunHandle>()

        override suspend fun submit(request: RunRequest): RunHandle {
            submitted += request
            val handle = RunHandle(
                id = "run-${submitted.size}",
                conversationId = request.conversationId ?: Uuid.random(),
                timeoutMs = request.timeoutMs,
                missionId = request.missionId,
            )
            handles += handle
            return handle
        }

        override suspend fun awaitTerminal(handle: RunHandle): RunResult =
            error("awaitTerminal is not exercised by MissionSupervisorTest")

        override suspend fun cancel(handle: RunHandle) = Unit

        override suspend fun resume(runId: String): RunResult? = null

        override fun events(handle: RunHandle): Flow<List<RunEventEntity>> = flowOf(emptyList())
    }

    private class FakeConversationProbe : MissionSupervisor.MissionConversationProbe {
        var userSpokeLastResult = false
        var lastAssistantTextResult: String? = null
        var userSpokeLastCalls = 0
        var lastAssistantTextCalls = 0

        override suspend fun userSpokeLast(conversationId: String): Boolean {
            userSpokeLastCalls++
            return userSpokeLastResult
        }

        override suspend fun lastAssistantText(conversationId: String): String? {
            lastAssistantTextCalls++
            return lastAssistantTextResult
        }
    }

    private class Fixture {
        val missionDao = FakeMissionDao()
        val runEventDao = FakeRunEventDao()
        val runEvents = RunEventRepository(runEventDao)
        val missionRepo = MissionRepository(missionDao, runEvents)
        val runService = FakeRunService()
        val conversations = FakeConversationProbe()
        val runStatuses = mutableMapOf<String, String?>()
        val runKinds = mutableMapOf<String, String?>()
        val tokens = mutableMapOf<String, Long>()
        val notifications = mutableListOf<Pair<String, String>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val supervisor = MissionSupervisor(
            missionRepository = missionRepo,
            launcher = MissionLauncher(
                missionRepository = missionRepo,
                runService = runService,
                runEventRepository = runEvents,
                assistantResolver = { Uuid.random() },
            ),
            runEventRepository = runEvents,
            runStatusProbe = MissionSupervisor.RunStatusProbe { runId -> runStatuses[runId] },
            runKindProbe = { runId -> runKinds[runId] },
            conversationProbe = conversations,
            notify = { title, content -> notifications += title to content },
            appScope = scope,
            tokenProbe = { conversationId -> tokens[conversationId] ?: 0L },
        )

        suspend fun seed(mission: MissionEntity) = missionDao.upsert(mission)

        /** Emit a RUN_FINISHED event for the run and return the journal row. */
        suspend fun finishedEvent(runId: String, missionId: String): RunEventEntity {
            runEvents.emit(
                type = RunEventType.RUN_FINISHED,
                title = "finished",
                missionId = missionId,
                runId = runId,
            )
            return runEventDao.snapshot().first {
                it.runId == runId && it.type == RunEventType.RUN_FINISHED.name
            }
        }

        /** Release the global registries a resumed launch touched. */
        fun cleanupLaunches() {
            runService.handles.forEach { handle ->
                MissionSessions.unregister(handle.conversationId.toString())
                ConversationSystemAddendum.clear(handle.conversationId)
            }
        }
    }

    private fun mission(
        id: String,
        conversationId: String? = null,
        status: String = MissionStatus.ACTIVE.name,
        resumeCount: Int = 0,
        executionState: String = MissionExecutionState.RUNNING.name,
        lastRunId: String? = null,
        acceptanceJson: String = "[]",
        limitsJson: String? = null,
    ) = MissionEntity(
        id = id,
        title = "写周报",
        description = "总结本周进展",
        status = status,
        acceptanceJson = acceptanceJson,
        conversationId = conversationId,
        limitsJson = limitsJson,
        resumeCount = resumeCount,
        executionState = executionState,
        lastRunId = lastRunId,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    // ---------------------------------------------------------------- signals

    @Test
    fun `done signal completes the mission and notifies the delivery summary`() = runBlocking {
        val fx = Fixture()
        fx.seed(mission(id = "m1", conversationId = CONV1))
        fx.runStatuses["r1"] = "succeeded"
        fx.runEvents.emit(
            type = RunEventType.NOTE,
            title = "mission_report: done",
            payloadJson = """{"mission_signal":"done","note":"交付摘要：周报已写入工作区"}""",
            missionId = "m1",
            conversationId = CONV1,
            runId = "r1",
        )
        val finished = fx.finishedEvent(runId = "r1", missionId = "m1")

        fx.supervisor.handleRunFinished(finished)

        val row = fx.missionDao.getById("m1")!!
        assertEquals(MissionStatus.DONE.name, row.status)
        assertEquals(MissionExecutionState.IDLE.name, row.executionState)
        assertTrue("done must never submit another round", fx.runService.submitted.isEmpty())
        val notice = fx.notifications.single()
        assertEquals("任务完成：写周报", notice.first)
        assertEquals("交付摘要：周报已写入工作区", notice.second)
    }

    @Test
    fun `blocked signal parks the mission and notifies the reason`() = runBlocking {
        val fx = Fixture()
        fx.seed(mission(id = "m2", conversationId = CONV1))
        fx.runEvents.emit(
            type = RunEventType.NOTE,
            title = "mission_report: blocked",
            payloadJson = """{"mission_signal":"blocked","note":"需要用户提供 API key"}""",
            missionId = "m2",
            conversationId = CONV1,
            runId = "r2",
        )
        val finished = fx.finishedEvent(runId = "r2", missionId = "m2")

        fx.supervisor.handleRunFinished(finished)

        val row = fx.missionDao.getById("m2")!!
        assertEquals(MissionStatus.BLOCKED.name, row.status)
        assertEquals(MissionExecutionState.BLOCKED.name, row.executionState)
        assertTrue(fx.runService.submitted.isEmpty())
        val notice = fx.notifications.single()
        assertEquals("任务已停下：写周报", notice.first)
        assertEquals("blocked by model", notice.second)
    }

    // ---------------------------------------------------------------- resume

    @Test
    fun `no signal with progress resumes in the same conversation with a handoff`() = runBlocking {
        val fx = Fixture()
        fx.seed(mission(id = "m3", conversationId = CONV1))
        fx.conversations.lastAssistantTextResult = "上一轮结尾：已完成大纲"
        fx.runEvents.emit(
            type = RunEventType.TOOL_RESULT,
            title = "workspace_write_file",
            missionId = "m3",
            conversationId = CONV1,
            runId = "r3",
        )
        fx.runEvents.emit(
            type = RunEventType.ARTIFACT,
            title = "/workspace/outline.md",
            missionId = "m3",
            conversationId = CONV1,
        )
        val finished = fx.finishedEvent(runId = "r3", missionId = "m3")

        fx.supervisor.handleRunFinished(finished)

        val request = fx.runService.submitted.single()
        assertEquals(AgentRunKind.Mission, request.kind)
        assertEquals("m3", request.missionId)
        assertEquals(Uuid.parse(CONV1), request.conversationId)
        assertTrue("handoff prompt is composed", request.prompt.contains("[任务督导]"))
        assertTrue("artifacts ride into the handoff", request.prompt.contains("/workspace/outline.md"))
        assertTrue("last run tail rides into the handoff", request.prompt.contains("上一轮结尾：已完成大纲"))

        val row = fx.missionDao.getById("m3")!!
        assertEquals(1, row.resumeCount)
        assertEquals(MissionExecutionState.RUNNING.name, row.executionState)
        fx.cleanupLaunches()
    }

    @Test
    fun `resume count at the max rounds blocks instead of resuming`() = runBlocking {
        val fx = Fixture()
        fx.seed(mission(id = "m4", conversationId = CONV1, resumeCount = 5))
        fx.runEvents.emit(
            type = RunEventType.TOOL_RESULT,
            title = "tool",
            missionId = "m4",
            runId = "r4",
        )
        val finished = fx.finishedEvent(runId = "r4", missionId = "m4")

        fx.supervisor.handleRunFinished(finished)

        assertEquals(MissionStatus.BLOCKED.name, fx.missionDao.getById("m4")!!.status)
        assertTrue("a circuit breaker must not submit", fx.runService.submitted.isEmpty())
        assertEquals("熔断:轮数", fx.notifications.single().second)
    }

    @Test
    fun `two consecutive no-progress rounds trip the stall breaker`() = runBlocking {
        val fx = Fixture()
        fx.seed(mission(id = "m5", conversationId = CONV1))

        // Round 1 ends without any progress event → stall #1, still worth one resume.
        fx.supervisor.handleRunFinished(fx.finishedEvent(runId = "r5a", missionId = "m5"))
        assertEquals(1, fx.runService.submitted.size)
        assertEquals(1, fx.missionDao.getById("m5")!!.resumeCount)

        // The resumed run ends without progress → stall #2 ≥ threshold → blocked.
        val resumedRunId = fx.runService.handles.single().id
        fx.supervisor.handleRunFinished(fx.finishedEvent(runId = resumedRunId, missionId = "m5"))

        assertTrue("the stall breaker must not submit again", fx.runService.submitted.size == 1)
        assertEquals(MissionStatus.BLOCKED.name, fx.missionDao.getById("m5")!!.status)
        assertEquals("熔断:无进展", fx.notifications.last().second)
        fx.cleanupLaunches()
    }

    @Test
    fun `token budget overrun blocks the round instead of resuming`() = runBlocking {
        val fx = Fixture()
        fx.seed(
            mission(
                id = "m12",
                conversationId = CONV1,
                limitsJson = JsonInstant.encodeToString(MissionLimits(tokenBudget = 1_000L)),
            )
        )
        // Usage ledger says this mission's execution conversation already burnt 5k tokens.
        fx.tokens[CONV1] = 5_000L
        fx.runEvents.emit(
            type = RunEventType.TOOL_RESULT,
            title = "tool",
            missionId = "m12",
            runId = "r12",
        )
        fx.supervisor.handleRunFinished(fx.finishedEvent(runId = "r12", missionId = "m12"))

        assertTrue("the token breaker must not submit", fx.runService.submitted.isEmpty())
        assertEquals(MissionStatus.BLOCKED.name, fx.missionDao.getById("m12")!!.status)
        assertEquals("熔断:预算", fx.notifications.single().second)
    }

    @Test
    fun `done records the model goal self-assessment`() = runBlocking {
        val fx = Fixture()
        fx.seed(
            mission(
                id = "m13",
                conversationId = CONV1,
                acceptanceJson = JsonInstant.encodeToString(listOf("完成大纲", "写入文件")),
            )
        )
        fx.runEvents.emit(
            type = RunEventType.NOTE,
            title = "mission_report: done",
            payloadJson = """{"mission_signal":"done","note":"只完成了第二步","goals":[1]}""",
            missionId = "m13",
            conversationId = CONV1,
            runId = "r13",
        )
        fx.supervisor.handleRunFinished(fx.finishedEvent(runId = "r13", missionId = "m13"))

        assertEquals(
            "only the reported goal index is checked",
            "[1]",
            fx.missionDao.getById("m13")!!.acceptanceCheckedJson,
        )
    }

    @Test
    fun `done without goals marks every goal met`() = runBlocking {
        val fx = Fixture()
        fx.seed(
            mission(
                id = "m14",
                conversationId = CONV1,
                acceptanceJson = JsonInstant.encodeToString(listOf("完成大纲", "写入文件")),
            )
        )
        fx.runEvents.emit(
            type = RunEventType.NOTE,
            title = "mission_report: done",
            payloadJson = """{"mission_signal":"done","note":"全部完成"}""",
            missionId = "m14",
            conversationId = CONV1,
            runId = "r14",
        )
        fx.supervisor.handleRunFinished(fx.finishedEvent(runId = "r14", missionId = "m14"))

        assertEquals(
            "an omitted goals array means the model claims every goal",
            "[0,1]",
            fx.missionDao.getById("m14")!!.acceptanceCheckedJson,
        )
    }

    @Test
    fun `a user in the conversation yields instead of resuming`() = runBlocking {
        val fx = Fixture()
        fx.seed(mission(id = "m6", conversationId = CONV1))
        fx.conversations.userSpokeLastResult = true
        fx.runEvents.emit(
            type = RunEventType.TOOL_RESULT,
            title = "tool",
            missionId = "m6",
            runId = "r6",
        )
        val finished = fx.finishedEvent(runId = "r6", missionId = "m6")

        fx.supervisor.handleRunFinished(finished)

        assertTrue(fx.runService.submitted.isEmpty())
        assertEquals(MissionStatus.ACTIVE.name, fx.missionDao.getById("m6")!!.status)
        assertEquals(0, fx.missionDao.getById("m6")!!.resumeCount)
        assertEquals(1, fx.conversations.userSpokeLastCalls)
        assertTrue("yielding stays silent", fx.notifications.isEmpty())
    }

    @Test
    fun `a cancelled run parks the mission idle and is never resurrected`() = runBlocking {
        val fx = Fixture()
        fx.seed(
            mission(
                id = "m7",
                conversationId = CONV1,
                executionState = MissionExecutionState.RUNNING.name,
                lastRunId = "r7",
            )
        )
        fx.runStatuses["r7"] = "cancelled"
        val finished = fx.finishedEvent(runId = "r7", missionId = "m7")

        fx.supervisor.handleRunFinished(finished)

        assertTrue(fx.runService.submitted.isEmpty())
        assertEquals(MissionStatus.ACTIVE.name, fx.missionDao.getById("m7")!!.status)
        assertEquals(MissionExecutionState.IDLE.name, fx.missionDao.getById("m7")!!.executionState)
        assertTrue(fx.notifications.isEmpty())
    }

    @Test
    fun `a user stop parks the mission idle and is never auto-continued`() = runBlocking {
        val fx = Fixture()
        fx.seed(mission(id = "m7b", conversationId = CONV1))
        // The user pressed stop (detail page journals this NOTE), then the round still ended
        // with progress — the stop must win over the auto-continue decision.
        fx.runEvents.emit(
            type = RunEventType.NOTE,
            title = "user_stop",
            missionId = "m7b",
            runId = "r7b",
        )
        fx.runEvents.emit(
            type = RunEventType.TOOL_RESULT,
            title = "tool",
            missionId = "m7b",
            runId = "r7b",
        )
        val finished = fx.finishedEvent(runId = "r7b", missionId = "m7b")

        fx.supervisor.handleRunFinished(finished)

        assertTrue("a stopped mission is never auto-continued", fx.runService.submitted.isEmpty())
        assertEquals(MissionStatus.ACTIVE.name, fx.missionDao.getById("m7b")!!.status)
        assertEquals(MissionExecutionState.IDLE.name, fx.missionDao.getById("m7b")!!.executionState)
        assertTrue(fx.notifications.isEmpty())
    }

    @Test
    fun `a mission_dispatch child run never drives the mission`() = runBlocking {
        val fx = Fixture()
        fx.seed(mission(id = "m8", conversationId = CONV1, lastRunId = "r8"))
        // The child carries the parent missionId for journaling, but it is a SubAgent run.
        fx.runKinds["r8-child"] = AgentRunKind.SubAgent.wire
        fx.runEvents.emit(
            type = RunEventType.TOOL_RESULT,
            title = "tool",
            missionId = "m8",
            runId = "r8-child",
        )
        val finished = fx.finishedEvent(runId = "r8-child", missionId = "m8")

        fx.supervisor.handleRunFinished(finished)

        assertTrue("a child finishing must not start a mission round", fx.runService.submitted.isEmpty())
        assertEquals(MissionExecutionState.RUNNING.name, fx.missionDao.getById("m8")!!.executionState)
        assertTrue(fx.notifications.isEmpty())
    }

    @Test
    fun `a replayed older round cannot continue the live round`() = runBlocking {
        val fx = Fixture()
        // The process restarted mid-round: lastRunId is the resumed round. The journal tail
        // replayed on start still contains the PREVIOUS round's RUN_FINISHED.
        fx.seed(mission(id = "m8d", conversationId = CONV1, lastRunId = "r-current"))
        fx.runEvents.emit(
            type = RunEventType.TOOL_RESULT,
            title = "tool",
            missionId = "m8d",
            runId = "r-old",
        )
        val finished = fx.finishedEvent(runId = "r-old", missionId = "m8d")

        fx.supervisor.handleRunFinished(finished)

        assertTrue("a stale round must not launch a duplicate", fx.runService.submitted.isEmpty())
        assertEquals(MissionExecutionState.RUNNING.name, fx.missionDao.getById("m8d")!!.executionState)
    }

    @Test
    fun `a round ending after the user parked the mission is not continued`() = runBlocking {
        val fx = Fixture()
        // The stop button parks the posture IDLE before the generation finishes winding down.
        fx.seed(
            mission(
                id = "m8b",
                conversationId = CONV1,
                executionState = MissionExecutionState.IDLE.name,
                lastRunId = "r8b",
            )
        )
        fx.runEvents.emit(
            type = RunEventType.TOOL_RESULT,
            title = "tool",
            missionId = "m8b",
            runId = "r8b",
        )
        val finished = fx.finishedEvent(runId = "r8b", missionId = "m8b")

        fx.supervisor.handleRunFinished(finished)

        assertTrue(fx.runService.submitted.isEmpty())
        assertEquals(MissionExecutionState.IDLE.name, fx.missionDao.getById("m8b")!!.executionState)
    }

    @Test
    fun `a round ending after the mission was completed only parks the posture`() = runBlocking {
        val fx = Fixture()
        fx.seed(
            mission(
                id = "m8c",
                conversationId = CONV1,
                status = MissionStatus.DONE.name,
                executionState = MissionExecutionState.RUNNING.name,
                lastRunId = "r8c",
            )
        )
        val finished = fx.finishedEvent(runId = "r8c", missionId = "m8c")

        fx.supervisor.handleRunFinished(finished)

        assertTrue(fx.runService.submitted.isEmpty())
        assertEquals(MissionStatus.DONE.name, fx.missionDao.getById("m8c")!!.status)
        assertEquals(MissionExecutionState.IDLE.name, fx.missionDao.getById("m8c")!!.executionState)
    }

    // ---------------------------------------------------------------- boot

    @Test
    fun `onBoot resumes a mission whose last run already died`() = runBlocking {
        val fx = Fixture()
        fx.seed(
            mission(
                id = "m8",
                conversationId = CONV1,
                executionState = MissionExecutionState.RUNNING.name,
                lastRunId = "X",
            )
        )
        fx.runStatuses["X"] = "failed"
        fx.runEvents.emit(
            type = RunEventType.TOOL_RESULT,
            title = "tool",
            missionId = "m8",
            runId = "X",
        )

        fx.supervisor.onBoot()

        assertEquals(1, fx.runService.submitted.size)
        assertEquals(Uuid.parse(CONV1), fx.runService.submitted.single().conversationId)
        assertEquals(1, fx.missionDao.getById("m8")!!.resumeCount)
        fx.cleanupLaunches()
    }

    @Test
    fun `onBoot leaves a mission alone while its run still looks alive`() = runBlocking {
        val fx = Fixture()
        fx.seed(
            mission(
                id = "m9",
                conversationId = CONV2,
                executionState = MissionExecutionState.RUNNING.name,
                lastRunId = "Y",
            )
        )
        fx.runStatuses["Y"] = "running"

        fx.supervisor.onBoot()

        assertTrue(fx.runService.submitted.isEmpty())
        assertEquals(MissionExecutionState.RUNNING.name, fx.missionDao.getById("m9")!!.executionState)
    }

    // ---------------------------------------------------------------- journal subscription

    @Test
    fun `start subscribes once and reacts to run-finished events from the journal`() = runBlocking {
        val fx = Fixture()
        fx.seed(mission(id = "m10", conversationId = CONV1))
        fx.runEvents.emit(
            type = RunEventType.NOTE,
            title = "mission_report: done",
            payloadJson = """{"mission_signal":"done","note":"done via journal"}""",
            missionId = "m10",
            runId = "r10",
        )
        fx.runEvents.emit(
            type = RunEventType.RUN_FINISHED,
            title = "finished",
            missionId = "m10",
            runId = "r10",
        )

        fx.supervisor.start()
        fx.supervisor.start() // idempotent — must not double-subscribe or throw

        // Wait for the FULL reaction — status flip AND notification. Watching status alone
        // can fire while complete() is still between its transition and notify steps.
        val deadline = System.currentTimeMillis() + 10_000L
        while (
            System.currentTimeMillis() < deadline &&
            (fx.missionDao.getById("m10")!!.status != MissionStatus.DONE.name ||
                fx.notifications.isEmpty())
        ) {
            delay(20)
        }

        assertEquals(MissionStatus.DONE.name, fx.missionDao.getById("m10")!!.status)
        assertEquals("done via journal", fx.notifications.single().second)
        fx.scope.cancel()
    }

    // ---------------------------------------------------------------- recurring missions

    @Test
    fun `done signal on a recurring mission ends the occurrence not the mission`() = runBlocking {
        val fx = Fixture()
        val recurring = mission(id = "m11", conversationId = CONV1, resumeCount = 2)
            .copy(triggerJson = """{"type":"power_connected"}""")
        fx.seed(recurring)
        fx.runStatuses["r11"] = "succeeded"
        fx.runEvents.emit(
            type = RunEventType.NOTE,
            title = "mission_report: done",
            payloadJson = """{"mission_signal":"done","note":"本轮交付：电源事件周报已更新"}""",
            missionId = "m11",
            conversationId = CONV1,
            runId = "r11",
        )
        val finished = fx.finishedEvent(runId = "r11", missionId = "m11")

        fx.supervisor.handleRunFinished(finished)

        val row = fx.missionDao.getById("m11")!!
        assertEquals(
            "a recurring mission stays ACTIVE for the next fire",
            MissionStatus.ACTIVE.name,
            row.status,
        )
        assertEquals(MissionExecutionState.IDLE.name, row.executionState)
        assertEquals("the next occurrence gets a fresh resume budget", 0, row.resumeCount)
        assertTrue("the occurrence end must not launch a continuation", fx.runService.submitted.isEmpty())

        val occurrence = fx.runEventDao.snapshot().single {
            it.missionId == "m11" && it.title == "occurrence_done"
        }
        assertEquals(RunEventType.NOTE.name, occurrence.type)
        assertEquals("本轮交付：电源事件周报已更新", occurrence.detail)

        val notice = fx.notifications.single()
        assertTrue("the notification announces the occurrence", notice.first.contains("本轮完成"))
        assertEquals("本轮交付：电源事件周报已更新", notice.second)
    }

    // ---------------------------------------------------------------- completion gate (批 D)

    @Test
    fun `done with confirmation required parks the mission in review`() = runBlocking {
        val fx = Fixture()
        fx.seed(
            mission(
                id = "m15",
                conversationId = CONV1,
                acceptanceJson = JsonInstant.encodeToString(listOf("完成大纲")),
                limitsJson = """{"maxResumeRounds":5,"stallRounds":2,"tokenBudget":500000,"requireConfirmation":true}""",
            )
        )
        fx.runStatuses["r15"] = "succeeded"
        fx.runEvents.emit(
            type = RunEventType.NOTE,
            title = "mission_report: done",
            payloadJson = """{"mission_signal":"done","note":"交付摘要：周报已写入工作区"}""",
            missionId = "m15",
            conversationId = CONV1,
            runId = "r15",
        )
        fx.supervisor.handleRunFinished(fx.finishedEvent(runId = "r15", missionId = "m15"))

        val row = fx.missionDao.getById("m15")!!
        assertEquals("a done signal must not close a gated mission", MissionStatus.REVIEW.name, row.status)
        assertEquals(MissionExecutionState.IDLE.name, row.executionState)
        assertTrue("the gate must never submit another round", fx.runService.submitted.isEmpty())
        assertEquals("the self-assessment still runs", "[0]", row.acceptanceCheckedJson)

        val awaiting = fx.runEventDao.snapshot().single {
            it.missionId == "m15" && it.title == "awaiting_review"
        }
        assertEquals(RunEventType.NOTE.name, awaiting.type)
        assertEquals("交付摘要：周报已写入工作区", awaiting.detail)

        val notice = fx.notifications.single()
        assertEquals("任务待确认：写周报", notice.first)
        assertEquals("交付摘要：周报已写入工作区", notice.second)
    }

    @Test
    fun `a recurring mission ignores the confirmation gate`() = runBlocking {
        val fx = Fixture()
        val recurring = mission(
            id = "m16",
            conversationId = CONV1,
            limitsJson = """{"maxResumeRounds":5,"stallRounds":2,"tokenBudget":500000,"requireConfirmation":true}""",
        ).copy(triggerJson = """{"type":"power_connected"}""")
        fx.seed(recurring)
        fx.runStatuses["r16"] = "succeeded"
        fx.runEvents.emit(
            type = RunEventType.NOTE,
            title = "mission_report: done",
            payloadJson = """{"mission_signal":"done","note":"本轮完成"}""",
            missionId = "m16",
            conversationId = CONV1,
            runId = "r16",
        )
        fx.supervisor.handleRunFinished(fx.finishedEvent(runId = "r16", missionId = "m16"))

        assertEquals(
            "an occurrence end stays ACTIVE for the next fire",
            MissionStatus.ACTIVE.name,
            fx.missionDao.getById("m16")!!.status,
        )
        assertTrue(fx.runEventDao.snapshot().any { it.missionId == "m16" && it.title == "occurrence_done" })
    }

    // ---------------------------------------------------------------- mission_report tool

    @Test
    fun `mission_report tool forwards valid calls and rejects unknown statuses`() = runBlocking {
        val calls = mutableListOf<Triple<String, String?, List<Int>>>()
        val tool = buildMissionReportTool { status, note, goals ->
            calls += Triple(status, note, goals)
            """{"ok":true,"status":"$status"}"""
        }

        assertEquals("mission_report", tool.name)

        val okParts = tool.execute(buildJsonObject {
            put("status", "done")
            put("note", "交付摘要")
        })
        val okText = (okParts.single() as UIMessagePart.Text).text
        assertTrue("the reporter's return string is passed through", okText.contains("\"ok\":true"))
        assertEquals(
            listOf(Triple("done", "交付摘要", emptyList<Int>())),
            calls,
        )

        val withGoals = tool.execute(buildJsonObject {
            put("status", "done")
            put("note", "部分完成")
            put("goals", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(0))
                add(kotlinx.serialization.json.JsonPrimitive(2))
            })
        })
        assertTrue((withGoals.single() as UIMessagePart.Text).text.contains("\"ok\":true"))
        assertEquals(
            "goal indices reach the reporter",
            Triple("done", "部分完成", listOf(0, 2)),
            calls.last(),
        )

        val badParts = tool.execute(buildJsonObject { put("status", "finished") })
        assertTrue(
            (badParts.single() as UIMessagePart.Text).text.contains("invalid_status"),
        )
        assertEquals("invalid calls never reach the reporter", 2, calls.size)

        val missingParts = tool.execute(buildJsonObject { })
        assertTrue(
            (missingParts.single() as UIMessagePart.Text).text.contains("invalid_status"),
        )
        assertEquals("missing status never reaches the reporter", 2, calls.size)
    }
}
