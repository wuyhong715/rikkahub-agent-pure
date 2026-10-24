package me.rerere.rikkahub.service.mission

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
import me.rerere.rikkahub.runtime.util.JsonInstant
import me.rerere.rikkahub.service.run.RunHandle
import me.rerere.rikkahub.service.run.RunRequest
import me.rerere.rikkahub.service.run.RunResult
import me.rerere.rikkahub.service.run.RunService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_700_000_000_000L
private const val MINUTE = 60_000L

/**
 * 批 H — 启动入口的时间维度：到点拒启、按任务配置改单轮上限、进收尾窗口加指令。
 */
class MissionLauncherTimeTest {

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

    private class Fixture(nowMs: Long = NOW) {
        val missionDao = FakeMissionDao()
        val runEvents = RunEventRepository(FakeRunEventDao())
        val missionRepo = MissionRepository(missionDao, runEvents)
        val runService = FakeRunService()
        val started = mutableListOf<Pair<String, String>>()
        val launcher = MissionLauncher(
            missionRepository = missionRepo,
            runService = runService,
            runEventRepository = runEvents,
            assistantResolver = { Uuid.random() },
            now = { nowMs },
            onRoundStarted = { missionId, label -> started += missionId to label },
        )
    }

    private fun mission(limits: MissionLimits) = MissionEntity(
        id = "m1",
        title = "调研报告",
        description = "写一份报告",
        status = MissionStatus.ACTIVE.name,
        conversationId = "55555555-5555-5555-5555-555555555555",
        workspaceId = null,
        limitsJson = JsonInstant.encodeToString(limits),
        executionState = MissionExecutionState.IDLE.name,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `a passed deadline refuses the launch`() = runBlocking {
        val fx = Fixture()
        fx.missionDao.upsert(mission(MissionLimits(deadlineAt = NOW - MINUTE)))

        val outcome = fx.launcher.launch("m1", LaunchSource.Continue(2), continuePrompt = "继续")

        assertFalse(outcome.ok)
        assertTrue(outcome.error.orEmpty().contains("截止"))
        assertTrue("到点不该起 run", fx.runService.submitted.isEmpty())
        assertTrue("也不该点亮前台服务", fx.started.isEmpty())
    }

    @Test
    fun `the per-round limit from the mission config reaches the run request`() = runBlocking {
        val fx = Fixture()
        fx.missionDao.upsert(mission(MissionLimits(roundTimeoutMinutes = 90)))

        val outcome = fx.launcher.launch("m1", LaunchSource.Create)

        assertTrue(outcome.ok)
        assertEquals(90 * MINUTE, fx.runService.submitted.single().timeoutMs)
        assertTrue(fx.started.single().second.contains("启动"))
    }

    @Test
    fun `near the deadline the round is shortened to the remaining time`() = runBlocking {
        val fx = Fixture()
        fx.missionDao.upsert(
            mission(MissionLimits(roundTimeoutMinutes = 90, deadlineAt = NOW + 12 * MINUTE))
        )

        fx.launcher.launch("m1", LaunchSource.Create)

        assertEquals("不能超过截止时间", 12 * MINUTE, fx.runService.submitted.single().timeoutMs)
    }

    @Test
    fun `inside the wrap-up window the prompt gains the wrap-up directive`() = runBlocking {
        val fx = Fixture()
        fx.missionDao.upsert(
            mission(
                MissionLimits(
                    deadlineAt = NOW + 3 * MINUTE,
                    wrapUpMinutes = 5,
                )
            )
        )

        fx.launcher.launch("m1", LaunchSource.Create)

        val prompt = fx.runService.submitted.single().prompt
        assertTrue(prompt.contains("收尾轮"))
        assertTrue(prompt.contains("不要启动新的"))
    }

    @Test
    fun `structured acceptance is injected into the prompt`() = runBlocking {
        val fx = Fixture()
        fx.missionDao.upsert(
            mission(
                MissionLimits(
                    acceptanceChecks = listOf(
                        AcceptanceCheck(text = "测试通过", kind = AcceptanceKind.COMMAND, target = "pytest -q")
                    )
                )
            )
        )

        fx.launcher.launch("m1", LaunchSource.Create)

        val prompt = fx.runService.submitted.single().prompt
        assertTrue(prompt.contains("验收条件"))
        assertTrue(prompt.contains("pytest -q"))
    }
}
