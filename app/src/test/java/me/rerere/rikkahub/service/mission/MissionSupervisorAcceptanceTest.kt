package me.rerere.rikkahub.service.mission

import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum
import me.rerere.rikkahub.data.mission.AcceptanceCheck
import me.rerere.rikkahub.data.mission.AcceptanceKind
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
import me.rerere.rikkahub.runtime.util.JsonInstant
import me.rerere.rikkahub.service.run.RunHandle
import me.rerere.rikkahub.service.run.RunRequest
import me.rerere.rikkahub.service.run.RunResult
import me.rerere.rikkahub.service.run.RunService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CONV = "44444444-4444-4444-4444-444444444444"
private const val NOW = 1_700_000_000_000L

/**
 * 批 H — 督导层的两道新闸门：结构化验收（拦住假完成）与截止时间（拦住超时续跑）。
 */
class MissionSupervisorAcceptanceTest {

    private class FakeRunService : RunService {
        val submitted = mutableListOf<RunRequest>()

        override suspend fun submit(request: RunRequest): RunHandle {
            submitted += request
            return RunHandle(
                id = "run-${submitted.size}",
                conversationId = request.conversationId ?: Uuid.random(),
                timeoutMs = request.timeoutMs,
                missionId = request.missionId,
            )
        }

        override suspend fun awaitTerminal(handle: RunHandle): RunResult = error("not exercised")
        override suspend fun cancel(handle: RunHandle) = Unit
        override suspend fun resume(runId: String): RunResult? = null
        override fun events(handle: RunHandle): Flow<List<RunEventEntity>> = flowOf(emptyList())
    }

    private class Fixture {
        val missionDao = FakeMissionDao()
        val runEvents = RunEventRepository(FakeRunEventDao())
        val missionRepo = MissionRepository(missionDao, runEvents)
        val runService = FakeRunService()
        val notifications = mutableListOf<Pair<String, String>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val statuses = mutableMapOf<String, String?>()
        val scripts = mutableMapOf<String, HookResult>()
        val idleMissions = mutableListOf<String>()

        val shell = MissionHookShell { _, command, _ -> scripts[command] ?: HookResult(exitCode = 0) }

        val launcher = MissionLauncher(
            missionRepository = missionRepo,
            runService = runService,
            runEventRepository = runEvents,
            assistantResolver = { Uuid.random() },
            now = { NOW },
        )

        val supervisor = MissionSupervisor(
            missionRepository = missionRepo,
            launcher = launcher,
            runEventRepository = runEvents,
            runStatusProbe = MissionSupervisor.RunStatusProbe { runId -> statuses[runId] },
            runKindProbe = { AgentRunKind.Mission.wire },
            conversationProbe = object : MissionSupervisor.MissionConversationProbe {
                override suspend fun userSpokeLast(conversationId: String) = false
                override suspend fun lastAssistantText(conversationId: String): String? = "上一轮干到这里"
            },
            notify = { title, content -> notifications += title to content },
            appScope = scope,
            hookExecutor = MissionHookExecutor(shell),
            acceptanceVerifier = MissionAcceptanceVerifier(shell),
            now = { NOW },
            onMissionIdle = { missionId -> idleMissions += missionId },
        )

        fun cleanup() {
            scope.cancel()
            MissionSessions.unregister(CONV)
            ConversationSystemAddendum.clear(Uuid.parse(CONV))
        }

        suspend fun signal(runId: String, missionId: String, title: String, payload: String) {
            runEvents.emit(
                type = RunEventType.NOTE,
                title = title,
                payloadJson = payload,
                missionId = missionId,
                conversationId = CONV,
                runId = runId,
            )
        }

        suspend fun finished(runId: String, missionId: String): RunEventEntity {
            runEvents.emit(
                type = RunEventType.RUN_FINISHED,
                title = "finished",
                missionId = missionId,
                runId = runId,
            )
            return runEvents.observeByRun(runId).first().first { it.type == RunEventType.RUN_FINISHED.name }
        }
    }

    private fun mission(
        limits: MissionLimits = MissionLimits(),
        workspaceId: String? = "ws1",
    ) = MissionEntity(
        id = "m1",
        title = "交付报告",
        description = "写一份带验证的报告",
        status = MissionStatus.ACTIVE.name,
        conversationId = CONV,
        workspaceId = workspaceId,
        limitsJson = JsonInstant.encodeToString(limits),
        executionState = MissionExecutionState.RUNNING.name,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun donePayload() = """{"mission_signal":"done","note":"写完了"}"""

    @Test
    fun `failing acceptance refuses completion and asks for a targeted rework`() = runBlocking {
        val fx = Fixture()
        try {
            fx.missionDao.upsert(
                mission(
                    MissionLimits(
                        acceptanceChecks = listOf(
                            AcceptanceCheck(text = "测试通过", kind = AcceptanceKind.COMMAND, target = "pytest -q")
                        )
                    )
                )
            )
            fx.statuses["r1"] = "succeeded"
            fx.scripts["pytest -q"] = HookResult(exitCode = 1, stderr = "2 failed")
            fx.signal("r1", "m1", "mission_report: done", donePayload())

            fx.supervisor.handleRunFinished(fx.finished("r1", "m1"))

            val row = fx.missionDao.getById("m1")!!
            assertEquals("验收没过就不能关闭任务", MissionStatus.ACTIVE.name, row.status)
            val prompt = fx.runService.submitted.single().prompt
            assertTrue(prompt.contains("验收条件没有通过"))
            assertTrue(prompt.contains("2 failed"))
            assertTrue("返工要求只说差的部分", prompt.contains("只补这些没通过的部分"))
            assertTrue(
                fx.runEvents.observeByMission("m1").first()
                    .any { it.title == "acceptance" && it.detail.orEmpty().startsWith("✗") },
            )
        } finally {
            fx.cleanup()
        }
    }

    @Test
    fun `passing acceptance lets the mission complete`() = runBlocking {
        val fx = Fixture()
        try {
            fx.missionDao.upsert(
                mission(
                    MissionLimits(
                        acceptanceChecks = listOf(
                            AcceptanceCheck(text = "报告存在", kind = AcceptanceKind.FILE_EXISTS, target = "report.md")
                        )
                    )
                )
            )
            fx.statuses["r1"] = "succeeded"
            fx.signal("r1", "m1", "mission_report: done", donePayload())

            fx.supervisor.handleRunFinished(fx.finished("r1", "m1"))

            assertEquals(MissionStatus.DONE.name, fx.missionDao.getById("m1")!!.status)
            assertTrue(fx.runService.submitted.isEmpty())
            assertTrue("收尾要熄灭前台服务登记", fx.idleMissions.contains("m1"))
        } finally {
            fx.cleanup()
        }
    }

    @Test
    fun `acceptance is checked before the before-done hook`() = runBlocking {
        val fx = Fixture()
        try {
            fx.missionDao.upsert(
                mission(
                    MissionLimits(
                        acceptanceChecks = listOf(
                            AcceptanceCheck(text = "报告存在", kind = AcceptanceKind.FILE_EXISTS, target = "report.md")
                        )
                    )
                )
            )
            fx.statuses["r1"] = "succeeded"
            fx.scripts["test -e 'report.md'"] = HookResult(exitCode = 1)
            fx.signal("r1", "m1", "mission_report: done", donePayload())

            fx.supervisor.handleRunFinished(fx.finished("r1", "m1"))

            assertEquals(MissionStatus.ACTIVE.name, fx.missionDao.getById("m1")!!.status)
            assertEquals(1, fx.runService.submitted.size)
        } finally {
            fx.cleanup()
        }
    }

    @Test
    fun `zero rework budget parks a failing acceptance to review`() = runBlocking {
        val fx = Fixture()
        try {
            fx.missionDao.upsert(
                mission(
                    MissionLimits(
                        maxCheckFailures = 0,
                        acceptanceChecks = listOf(
                            AcceptanceCheck(text = "测试通过", kind = AcceptanceKind.COMMAND, target = "pytest")
                        ),
                    )
                )
            )
            fx.statuses["r1"] = "succeeded"
            fx.scripts["pytest"] = HookResult(exitCode = 1, stderr = "boom")
            fx.signal("r1", "m1", "mission_report: done", donePayload())

            fx.supervisor.handleRunFinished(fx.finished("r1", "m1"))

            val row = fx.missionDao.getById("m1")!!
            assertEquals(MissionStatus.REVIEW.name, row.status)
            assertEquals(MissionExecutionState.IDLE.name, row.executionState)
            assertTrue(fx.runService.submitted.isEmpty())
            assertTrue(fx.notifications.single().first.contains("待确认"))
        } finally {
            fx.cleanup()
        }
    }

    @Test
    fun `a live deadline lifts the round cap so time is the real bound`() = runBlocking {
        val fx = Fixture()
        try {
            // expire 在两小时内，resumeCount 已到默认上限 5 轮。
            fx.missionDao.upsert(
                mission(
                    MissionLimits(
                        maxResumeRounds = 2,
                        deadlineAt = NOW + 120 * 60_000L,
                    )
                ).copy(resumeCount = 5)
            )
            fx.statuses["r1"] = "succeeded"
            fx.signal("r1", "m1", "launch", "{}")

            fx.supervisor.handleRunFinished(fx.finished("r1", "m1"))

            assertEquals("有截止时间时不该被轮数熔断", 1, fx.runService.submitted.size)
        } finally {
            fx.cleanup()
        }
    }

    @Test
    fun `an expired deadline parks the mission instead of resuming`() = runBlocking {
        val fx = Fixture()
        try {
            fx.missionDao.upsert(mission(MissionLimits(deadlineAt = NOW - 1)))
            fx.statuses["r1"] = "succeeded"
            // 没有 done 信号 → 督导本来会续跑；截止时间必须先拦住它。
            fx.signal("r1", "m1", "launch", "{}")

            fx.supervisor.handleRunFinished(fx.finished("r1", "m1"))

            val row = fx.missionDao.getById("m1")!!
            assertEquals(MissionStatus.REVIEW.name, row.status)
            assertEquals(MissionExecutionState.IDLE.name, row.executionState)
            assertTrue("到点之后不许再起新一轮", fx.runService.submitted.isEmpty())
            assertTrue(fx.notifications.single().first.contains("到时间"))
        } finally {
            fx.cleanup()
        }
    }
}
