package me.rerere.rikkahub.service.mission

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.mission.FakeMissionDao
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionHook
import me.rerere.rikkahub.data.mission.MissionHookSpec
import me.rerere.rikkahub.data.mission.MissionLimits
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.runevent.FakeRunEventDao
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.runtime.util.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CONV = "66666666-6666-6666-6666-666666666666"

/**
 * 批 I — 工具级 hook：前置拦截、后置观察、匹配规则与「跑不起来就放行」的语义。
 */
class MissionToolHooksTest {

    private class Fixture {
        val missionDao = FakeMissionDao()
        val runEvents = RunEventRepository(FakeRunEventDao())
        val missionRepo = MissionRepository(missionDao, runEvents)
        val scripts = mutableMapOf<String, HookResult>()

        val hooks = MissionToolHooks(
            missionRepository = missionRepo,
            runEventRepository = runEvents,
            executor = MissionHookExecutor(
                shell = MissionHookShell { _, command, _ -> scripts[command] ?: HookResult(exitCode = 0) }
            ),
        )

        suspend fun seed(
            vararg hooks: MissionHook,
            workspaceId: String? = "ws1",
        ) {
            missionDao.upsert(
                MissionEntity(
                    id = "m1",
                    title = "交付",
                    description = "写文件",
                    status = MissionStatus.ACTIVE.name,
                    conversationId = CONV,
                    workspaceId = workspaceId,
                    limitsJson = JsonInstant.encodeToString(MissionLimits(hooks = hooks.toList())),
                    lastRunId = "r1",
                    createdAt = 1L,
                    updatedAt = 1L,
                )
            )
            MissionSessions.register(CONV, "m1")
        }

        suspend fun titles(): List<String> = runEvents.observeByMission("m1").first().map { it.title }
    }

    private fun beforeHook(
        command: String,
        tool: String,
        action: String = MissionHook.ACTION_BLOCK,
    ) = MissionHook(at = MissionHook.WHEN_TOOL_BEFORE, command = command, tool = tool, failAction = action)

    private fun afterHook(command: String, tool: String = "") =
        MissionHook(at = MissionHook.WHEN_TOOL_AFTER, command = command, tool = tool)

    @Test
    fun `a failing blocking pre-hook vetoes the tool call`() = runBlocking {
        val fx = Fixture()
        try {
            fx.seed(beforeHook("./policy.sh", "workspace_*"))
            fx.scripts["./policy.sh"] = HookResult(exitCode = 1, stderr = "禁止直推主干")

            val reason = fx.hooks.before(CONV, "workspace_write_file", "{}")

            assertTrue("必须返回理由才能拦截", reason != null)
            assertTrue(reason!!.contains("workspace_write_file"))
            assertTrue(reason.contains("禁止直推主干"))
            assertTrue(fx.titles().contains("tool_hook_blocked"))
        } finally {
            MissionSessions.unregister(CONV)
        }
    }

    @Test
    fun `a passing pre-hook lets the call through`() = runBlocking {
        val fx = Fixture()
        try {
            fx.seed(beforeHook("./policy.sh", "workspace_*"))
            assertNull(fx.hooks.before(CONV, "workspace_write_file", "{}"))
            assertTrue(fx.titles().isEmpty())
        } finally {
            MissionSessions.unregister(CONV)
        }
    }

    @Test
    fun `a warn pre-hook records but never blocks`() = runBlocking {
        val fx = Fixture()
        try {
            fx.seed(beforeHook("./policy.sh", "*", action = MissionHook.ACTION_WARN))
            fx.scripts["./policy.sh"] = HookResult(exitCode = 1, stderr = "style")

            assertNull(fx.hooks.before(CONV, "ssh_exec", "{}"))
            assertTrue(fx.titles().contains("tool_hook_warn"))
        } finally {
            MissionSessions.unregister(CONV)
        }
    }

    @Test
    fun `hooks only fire for matching tools`() = runBlocking {
        val fx = Fixture()
        try {
            fx.seed(beforeHook("./write-only.sh", "workspace_write_*"))
            fx.scripts["./write-only.sh"] = HookResult(exitCode = 1, stderr = "no")

            assertNull("读文件不该触发写文件的策略", fx.hooks.before(CONV, "workspace_read_file", "{}"))
            assertTrue(fx.hooks.before(CONV, "workspace_write_file", "{}") != null)
        } finally {
            MissionSessions.unregister(CONV)
        }
    }

    @Test
    fun `a pre-hook that cannot run lets the tool through`() = runBlocking {
        val fx = Fixture()
        try {
            fx.seed(beforeHook("./policy.sh", "*"), workspaceId = null)

            assertNull("没有工作区不等于调用有问题", fx.hooks.before(CONV, "ssh_exec", "{}"))
            assertTrue(fx.titles().contains("tool_hook_skipped"))
        } finally {
            MissionSessions.unregister(CONV)
        }
    }

    @Test
    fun `post-hooks never throw and record only failures`() = runBlocking {
        val fx = Fixture()
        try {
            fx.seed(afterHook("./lint.sh", "workspace_*"))
            fx.scripts["./lint.sh"] = HookResult(exitCode = 2, stderr = "2 problems")

            fx.hooks.after(CONV, "workspace_write_file", "{}")
            assertTrue(fx.titles().contains("tool_hook_failed"))

            fx.scripts["./lint.sh"] = HookResult(exitCode = 0)
            fx.hooks.after(CONV, "workspace_write_file", "{}")
            assertEquals("通过不该刷流水", 1, fx.titles().count { it == "tool_hook_failed" })
        } finally {
            MissionSessions.unregister(CONV)
        }
    }

    @Test
    fun `conversations outside a mission are ignored`() = runBlocking {
        val fx = Fixture()
        try {
            fx.seed(beforeHook("./policy.sh", "*"))
            fx.scripts["./policy.sh"] = HookResult(exitCode = 1)

            assertNull(fx.hooks.before("77777777-7777-7777-7777-777777777777", "ssh_exec", "{}"))
        } finally {
            MissionSessions.unregister(CONV)
        }
    }

    @Test
    fun `tool matching supports exact and prefix patterns`() {
        assertTrue(MissionHookSpec.matchesTool(MissionHook(tool = ""), "anything"))
        assertTrue(MissionHookSpec.matchesTool(MissionHook(tool = "*"), "anything"))
        assertTrue(MissionHookSpec.matchesTool(MissionHook(tool = "workspace_*"), "workspace_write_file"))
        assertFalse(MissionHookSpec.matchesTool(MissionHook(tool = "workspace_*"), "ssh_exec"))
        assertTrue(MissionHookSpec.matchesTool(MissionHook(tool = "SSH_EXEC"), "ssh_exec"))
        assertFalse(MissionHookSpec.matchesTool(MissionHook(tool = "ssh_exec"), "ssh_exec_extra"))
    }

    @Test
    fun `editor lines parse into tool hooks and round-trip`() {
        val hooks = MissionHookSpec.parseToolHookLines(
            """
            前: workspace_* | ./precheck.sh
            后: * | ./lint.sh 参数
            这一行没有冒号
            """.trimIndent(),
            failAction = MissionHook.ACTION_BLOCK,
        )
        assertEquals(2, hooks.size)
        assertEquals(MissionHook.WHEN_TOOL_BEFORE, hooks[0].at)
        assertEquals("workspace_*", hooks[0].tool)
        assertEquals("./precheck.sh", hooks[0].command)
        assertEquals(MissionHook.WHEN_TOOL_AFTER, hooks[1].at)
        assertEquals("显式写 * 就保留 *（语义等价于空）", "*", hooks[1].tool)
        assertEquals("./lint.sh 参数", hooks[1].command)

        val roundTrip = MissionHookSpec.parseToolHookLines(
            MissionHookSpec.toToolHookLines(hooks),
            failAction = MissionHook.ACTION_BLOCK,
        )
        assertEquals(hooks.map { it.at to it.tool }, roundTrip.map { it.at to it.tool })
        assertEquals(hooks.map { it.command }, roundTrip.map { it.command })
    }

    @Test
    fun `round hooks are not shown in the tool-hook editor`() {
        val hooks = MissionHookSpec.of(roundEnd = "./gate.sh")
        assertEquals("", MissionHookSpec.toToolHookLines(hooks))
    }

    @Test
    fun `runnableForTool filters by timing and tool`() {
        val limits = MissionLimits(
            hooks = MissionHookSpec.sanitize(
                listOf(
                    beforeHook("./a.sh", "workspace_*"),
                    afterHook("./b.sh", "workspace_*"),
                    beforeHook("./c.sh", "ssh_exec"),
                )
            )
        )
        val forWrite = MissionHookSpec.runnableForTool(limits, me.rerere.rikkahub.data.mission.MissionHookWhen.TOOL_BEFORE, "workspace_write_file")
        assertEquals(1, forWrite.size)
        assertEquals("./a.sh", forWrite.single().command)
    }
}
