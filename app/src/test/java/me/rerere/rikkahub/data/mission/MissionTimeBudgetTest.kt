package me.rerere.rikkahub.data.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 H — 时间预算的边界。这一层的错误不会在真机上以「崩溃」出现，只会以「任务晚停了半小时」
 * 或「该收尾时还在开新战线」出现，所以边界必须钉死在单测里。
 */
class MissionTimeBudgetTest {

    private val now = 1_000_000L
    private val minute = 60_000L

    @Test
    fun `no deadline uses the per-round limit and never wraps up`() {
        val plan = MissionTimeBudget.plan(MissionLimits(roundTimeoutMinutes = 45), now)
        assertEquals(45 * minute, plan.timeoutMs)
        assertFalse(plan.wrapUp)
        assertFalse(plan.expired)
        assertNull(plan.remainingMs)
    }

    @Test
    fun `deadline shortens the round to the remaining time`() {
        val limits = MissionLimits(
            roundTimeoutMinutes = 60,
            deadlineAt = now + 10 * minute,
            wrapUpMinutes = 5,
        )
        val plan = MissionTimeBudget.plan(limits, now)
        assertEquals(10 * minute, plan.timeoutMs)
        assertFalse("还剩 10 分钟，收尾窗口是 5 分钟，不算收尾轮", plan.wrapUp)
        assertEquals(10 * minute, plan.remainingMs)
    }

    @Test
    fun `inside the wrap-up window the round is marked as wrap-up`() {
        val limits = MissionLimits(
            roundTimeoutMinutes = 60,
            deadlineAt = now + 4 * minute,
            wrapUpMinutes = 5,
        )
        val plan = MissionTimeBudget.plan(limits, now)
        assertTrue(plan.wrapUp)
        assertFalse(plan.expired)
        assertEquals(4 * minute, plan.timeoutMs)
    }

    @Test
    fun `exactly at the deadline counts as expired`() {
        val plan = MissionTimeBudget.plan(MissionLimits(deadlineAt = now), now)
        assertTrue("到点就该拒绝启动，而不是给一个 1ms 的轮次", plan.expired)
        assertEquals(0L, plan.timeoutMs)
    }

    @Test
    fun `past the deadline is expired`() {
        val plan = MissionTimeBudget.plan(MissionLimits(deadlineAt = now - 1), now)
        assertTrue(plan.expired)
    }

    @Test
    fun `zero wrap-up window means no wrap-up round`() {
        val limits = MissionLimits(
            deadlineAt = now + 2 * minute,
            wrapUpMinutes = 0,
        )
        val plan = MissionTimeBudget.plan(limits, now)
        assertFalse("用户显式关掉收尾窗口时，到点直接停", plan.wrapUp)
    }

    @Test
    fun `round minutes are clamped into the supported range`() {
        assertEquals(MissionTimeBudget.MIN_ROUND_MIN, MissionTimeBudget.clampRoundMinutes(0))
        assertEquals(MissionTimeBudget.MAX_ROUND_MIN, MissionTimeBudget.clampRoundMinutes(10_000))
        assertEquals(45, MissionTimeBudget.clampRoundMinutes(45))
        assertEquals(0, MissionTimeBudget.clampWrapUpMinutes(-3))
        assertEquals(MissionTimeBudget.MAX_WRAPUP_MIN, MissionTimeBudget.clampWrapUpMinutes(999))
    }

    @Test
    fun `sanitized limits clamp the time fields`() {
        val limits = MissionLimits(roundTimeoutMinutes = 1, wrapUpMinutes = 999).sanitized()
        assertEquals(MissionTimeBudget.MIN_ROUND_MIN, limits.roundTimeoutMinutes)
        assertEquals(MissionTimeBudget.MAX_WRAPUP_MIN, limits.wrapUpMinutes)
    }

    @Test
    fun `wrap-up directive states the remaining minutes and forbids new work`() {
        val text = MissionTimeBudget.wrapUpDirective(4 * minute)
        assertTrue(text.contains("4 分钟"))
        assertTrue(text.contains("收尾轮"))
        assertTrue(text.contains("不要启动新的"))
    }

    @Test
    fun `remaining time formatting`() {
        assertEquals("已到期", MissionTimeBudget.formatRemaining(0))
        assertEquals("<1m", MissionTimeBudget.formatRemaining(30_000))
        assertEquals("12m", MissionTimeBudget.formatRemaining(12 * minute))
        assertEquals("2h 05m", MissionTimeBudget.formatRemaining(125 * minute))
    }
}
