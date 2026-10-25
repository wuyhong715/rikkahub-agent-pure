package me.rerere.rikkahub.data.mission

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.runevent.FakeRunEventDao
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.service.run.ConversationRunRegistry
import me.rerere.rikkahub.subagent.SubAgentRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [MissionEngine]'s signal persistence and approval-posture hooks.
 */
class MissionEngineTest {

    @Test
    fun `reportSignal uses the caller's mission id with a cold cache and no DB backfill`() = runBlocking {
        val missionDao = FakeMissionDao()
        val runEventDao = FakeRunEventDao()
        val runEvents = RunEventRepository(runEventDao)
        val missionRepo = MissionRepository(missionDao, runEvents)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MissionEngine(missionRepo, runEvents, SubAgentRegistry(), scope)

        try {
            // Deliberately no mission row and no binding: this is the first-round shape, where
            // the mission's conversation_id has not been persisted yet. The tool resolved the
            // mission while assembling its schema, so the signal must still land.
            engine.reportSignal(
                conversationId = "conv-fresh",
                runId = "run-1",
                status = "done",
                note = "交付摘要",
                missionId = "m-fresh",
            )

            val signal = runEventDao.snapshot()
                .single { it.type == RunEventType.NOTE.name && it.title == "mission_report: done" }
            assertEquals("m-fresh", signal.missionId)
            assertEquals("conv-fresh", signal.conversationId)
            assertEquals("run-1", signal.runId)
            assertEquals("交付摘要", signal.detail)
        } finally {
            scope.cancel()
        }
    }

    // ---------------------------------------------------------------- 批 E — approval posture

    @Test
    fun `awaiting approval parks a running mission and resolution restores it`() = runBlocking {
        val missionDao = FakeMissionDao()
        val runEventDao = FakeRunEventDao()
        val runEvents = RunEventRepository(runEventDao)
        val missionRepo = MissionRepository(missionDao, runEvents)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MissionEngine(missionRepo, runEvents, SubAgentRegistry(), scope)

        try {
            missionDao.upsert(
                MissionEntity(
                    id = "m-approval",
                    title = "整理相册",
                    description = "",
                    conversationId = "conv-approval",
                    executionState = MissionExecutionState.RUNNING.name,
                    lastRunId = "run-approval",
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                )
            )
            engine.bindConversation("conv-approval", "m-approval")

            engine.onAwaitingApproval("conv-approval", "workspace_write_file")

            assertEquals(
                MissionExecutionState.WAITING_APPROVAL.name,
                missionDao.getById("m-approval")!!.executionState,
            )
            val note = runEventDao.snapshot().single { it.title == "awaiting_approval" }
            assertEquals("workspace_write_file", note.detail)
            assertEquals("run-approval", note.runId)

            engine.onApprovalResolved("conv-approval")

            assertEquals(
                MissionExecutionState.RUNNING.name,
                missionDao.getById("m-approval")!!.executionState,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a late approval callback cannot resurrect a parked mission`() = runBlocking {
        val missionDao = FakeMissionDao()
        val runEventDao = FakeRunEventDao()
        val runEvents = RunEventRepository(runEventDao)
        val missionRepo = MissionRepository(missionDao, runEvents)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MissionEngine(missionRepo, runEvents, SubAgentRegistry(), scope)

        try {
            missionDao.upsert(
                MissionEntity(
                    id = "m-stopped",
                    title = "整理相册",
                    description = "",
                    conversationId = "conv-stopped",
                    executionState = MissionExecutionState.IDLE.name,
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                )
            )
            engine.bindConversation("conv-stopped", "m-stopped")

            engine.onAwaitingApproval("conv-stopped", "workspace_write_file")

            assertEquals(
                "a mission the user stopped must stay parked",
                MissionExecutionState.IDLE.name,
                missionDao.getById("m-stopped")!!.executionState,
            )
            assertEquals(
                "no approval event for a parked mission",
                0,
                runEventDao.snapshot().count { it.title == "awaiting_approval" },
            )
        } finally {
            scope.cancel()
        }
    }

    // ------------------------------------------------- P0 — progress events must be run-scoped

    /**
     * MissionSupervisor reads a finished round with `observeByRun(runId)`, which filters on
     * `run_id`. A TOOL_RESULT or ARTIFACT journaled without that column is invisible to it, so
     * the round scores as "no progress" no matter how much work it did.
     */
    @Test
    fun `tool result and artifact carry the mission's run id`() = runBlocking {
        val missionDao = FakeMissionDao()
        val runEventDao = FakeRunEventDao()
        val runEvents = RunEventRepository(runEventDao)
        val missionRepo = MissionRepository(missionDao, runEvents)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MissionEngine(missionRepo, runEvents, SubAgentRegistry(), scope)

        try {
            missionDao.upsert(
                MissionEntity(
                    id = "m-progress",
                    title = "跑一轮",
                    description = "",
                    conversationId = "conv-progress",
                    executionState = MissionExecutionState.RUNNING.name,
                    lastRunId = "run-live",
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                )
            )
            engine.bindConversation("conv-progress", "m-progress")

            engine.onToolExecuted(
                conversationId = "conv-progress",
                toolName = "write_text_file",
                argsJson = """{"path":"/workspace/out.md"}""",
                outputJson = "ok",
            )

            val events = runEventDao.snapshot()
            assertEquals("run-live", events.single { it.type == RunEventType.TOOL_RESULT.name }.runId)
            assertEquals("run-live", events.single { it.type == RunEventType.ARTIFACT.name }.runId)
        } finally {
            scope.cancel()
        }
    }

    /** A resumed round has no live binding but the mission still points at its run. */
    @Test
    fun `a live registry binding wins over the mission's last recorded run`() = runBlocking {
        val missionDao = FakeMissionDao()
        val runEventDao = FakeRunEventDao()
        val runEvents = RunEventRepository(runEventDao)
        val missionRepo = MissionRepository(missionDao, runEvents)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MissionEngine(missionRepo, runEvents, SubAgentRegistry(), scope)
        val conversation = kotlin.uuid.Uuid.random()

        try {
            missionDao.upsert(
                MissionEntity(
                    id = "m-live",
                    title = "第二轮",
                    description = "",
                    conversationId = conversation.toString(),
                    executionState = MissionExecutionState.RUNNING.name,
                    lastRunId = "run-previous",
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                )
            )
            ConversationRunRegistry.bind(conversation, "run-current", "mission")

            engine.onToolExecuted(
                conversationId = conversation.toString(),
                toolName = "read_file",
                argsJson = "{}",
                outputJson = "ok",
            )

            assertEquals(
                "run-current",
                runEventDao.snapshot().single { it.type == RunEventType.TOOL_RESULT.name }.runId,
            )
        } finally {
            ConversationRunRegistry.unbind(conversation)
            scope.cancel()
        }
    }
}
