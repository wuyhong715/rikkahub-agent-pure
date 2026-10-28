package me.rerere.rikkahub.data.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 F — 检查命令的纯规则：清洗、挑选、占位符展开、输出截断。
 *
 * 这些规则决定了「用户写的命令会不会被悄悄改掉」，所以逐条钉住。
 */
class MissionHookSpecTest {

    @Test
    fun `sanitize drops blank commands and normalises names`() {
        val cleaned = MissionHookSpec.sanitize(
            listOf(
                MissionHook(at = "ROUND_END", command = "  ./check.sh  ", timeoutSec = 1, failAction = "WARN"),
                MissionHook(at = "before_done", command = "   "),
                MissionHook(at = "unknown", command = "true"),
            )
        )

        assertEquals(2, cleaned.size)
        assertEquals("round_end", cleaned[0].at)
        assertEquals("./check.sh", cleaned[0].command)
        assertEquals(MissionHookSpec.MIN_TIMEOUT_SEC, cleaned[0].timeoutSec)
        assertEquals("warn", cleaned[0].failAction)
        // 未知时机按轮末处理（宁可多查一次，也不静默丢掉用户配的命令）
        assertEquals("round_end", cleaned[1].at)
    }

    @Test
    fun `sanitize clamps timeout and caps command length`() {
        val long = "x".repeat(MissionHookSpec.MAX_COMMAND_CHARS + 50)
        val cleaned = MissionHookSpec.sanitize(
            listOf(MissionHook(at = "round_end", command = long, timeoutSec = 99_999))
        ).single()

        assertEquals(MissionHookSpec.MAX_TIMEOUT_SEC, cleaned.timeoutSec)
        assertEquals(MissionHookSpec.MAX_COMMAND_CHARS, cleaned.command.length)
    }

    @Test
    fun `sanitize keeps at most MAX_HOOKS entries`() {
        val many = (1..MissionHookSpec.MAX_HOOKS + 4).map {
            MissionHook(at = "round_end", command = "echo $it")
        }
        assertEquals(MissionHookSpec.MAX_HOOKS, MissionHookSpec.sanitize(many).size)
    }

    @Test
    fun `runnable only returns matching non blank hooks`() {
        val limits = MissionLimits(
            hooks = listOf(
                MissionHook(at = "round_end", command = "true"),
                MissionHook(at = "before_done", command = ""),
            )
        )

        assertEquals(1, MissionHookSpec.runnable(limits, MissionHookWhen.ROUND_END).size)
        assertTrue(MissionHookSpec.runnable(limits, MissionHookWhen.BEFORE_DONE).isEmpty())
        assertTrue(MissionHookSpec.runnable(limits, MissionHookWhen.AFTER_FAILURE).isEmpty())
    }

    @Test
    fun `expand substitutes known placeholders and leaves shell braces alone`() {
        val expanded = MissionHookSpec.expand(
            "pytest -q --junit-xml=out/{round}.xml && echo {mission}/{run}/{status} \${HOME}",
            MissionHookContext(missionId = "m1", round = 3, runId = "r9", status = "failed"),
        )

        assertTrue(expanded.contains("out/3.xml"))
        assertTrue(expanded.contains("m1/r9/failed"))
        // shell 自己的 ${HOME} 不能被动
        assertTrue(expanded.contains("\${HOME}"))
    }

    @Test
    fun `expand leaves unknown placeholders untouched`() {
        val expanded = MissionHookSpec.expand("echo {nope} {round}", MissionHookContext("m", 2))
        assertTrue(expanded.contains("{nope}"))
        assertTrue(expanded.contains("2"))
    }

    @Test
    fun `clipOutput keeps the tail and reports what was dropped`() {
        val text = "a".repeat(100) + "FAILED: 3 tests"
        val clipped = MissionHookSpec.clipOutput(text, maxChars = 30)

        assertTrue(clipped.endsWith("FAILED: 3 tests"))
        assertTrue(clipped.contains("省略"))
        assertTrue(clipped.length < text.length)
    }

    @Test
    fun `of builds three hooks and drops the empty ones`() {
        val hooks = MissionHookSpec.of(roundEnd = "run-checks.sh", beforeDone = "")
        assertEquals(1, hooks.size)
        assertEquals(MissionHookWhen.ROUND_END, hooks.single().whenKind)
        assertEquals(MissionHook.ACTION_BLOCK, hooks.single().failAction)
    }

    @Test
    fun `commandFor and actionOf round trip`() {
        val hooks = MissionHookSpec.of(
            roundEnd = "a",
            beforeDone = "b",
            failAction = MissionHook.ACTION_WARN,
        )
        val limits = MissionLimits(hooks = hooks)

        assertEquals("a", MissionHookSpec.commandFor(limits, MissionHookWhen.ROUND_END))
        assertEquals("b", MissionHookSpec.commandFor(limits, MissionHookWhen.BEFORE_DONE))
        assertEquals("", MissionHookSpec.commandFor(limits, MissionHookWhen.AFTER_FAILURE))
        assertEquals(MissionHookAction.WARN, MissionHookSpec.actionOf(limits))
    }

    @Test
    fun `empty limits carry no hooks and keep legacy defaults`() {
        val defaults = MissionLimits()
        assertTrue(defaults.hooks.isEmpty())
        assertEquals(2, defaults.maxCheckFailures)
        assertEquals(12, defaults.maxResumeRounds)
        assertFalse(defaults.sanitized().hooks.any { it.isRunnable })
    }
}
