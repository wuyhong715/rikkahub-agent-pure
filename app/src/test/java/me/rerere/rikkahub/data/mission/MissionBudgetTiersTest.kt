package me.rerere.rikkahub.data.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 4 — 预算档位。这层出错的表现不是崩溃，而是「用户以为选了长跑，实际十分钟就被掐」，
 * 所以档位表、反查与夹取都用单测钉住。
 */
class MissionBudgetTiersTest {

    private fun defaults() = MissionLimits()

    @Test
    fun `fast tier writes all four budget fields`() {
        val limits = MissionBudgetTiers.apply(defaults(), MissionBudgetTiers.Tier.FAST)
        assertEquals(10, limits.roundTimeoutMinutes)
        assertEquals(4, limits.maxResumeRounds)
        assertEquals(300_000L, limits.tokenBudget)
        assertEquals(2, limits.wrapUpMinutes)
    }

    @Test
    fun `standard tier writes all four budget fields`() {
        val limits = MissionBudgetTiers.apply(defaults(), MissionBudgetTiers.Tier.STANDARD)
        assertEquals(60, limits.roundTimeoutMinutes)
        assertEquals(12, limits.maxResumeRounds)
        assertEquals(3_000_000L, limits.tokenBudget)
        assertEquals(10, limits.wrapUpMinutes)
    }

    @Test
    fun `long tier writes all four budget fields`() {
        val limits = MissionBudgetTiers.apply(defaults(), MissionBudgetTiers.Tier.LONG)
        assertEquals(180, limits.roundTimeoutMinutes)
        assertEquals(40, limits.maxResumeRounds)
        assertEquals(15_000_000L, limits.tokenBudget)
        assertEquals(30, limits.wrapUpMinutes)
    }

    @Test
    fun `match round-trips every tier`() {
        MissionBudgetTiers.Tier.entries.forEach { tier ->
            val limits = MissionBudgetTiers.apply(defaults(), tier)
            assertEquals("apply 之后必须能反查出同一个档位", tier, MissionBudgetTiers.match(limits))
        }
    }

    @Test
    fun `defaults are the standard tier`() {
        // 新建任务不填任何数字时用的就是 MissionLimits()，它必须等于标准档，
        // 否则 UI 会显示「自定义」而用户什么都没改。
        assertEquals(MissionBudgetTiers.Tier.STANDARD, MissionBudgetTiers.match(defaults()))
        assertEquals(60, MissionTimeBudget.DEFAULT_ROUND_MIN)
        assertEquals(10, MissionTimeBudget.DEFAULT_WRAPUP_MIN)
    }

    @Test
    fun `one field off makes it custom`() {
        val limits = MissionBudgetTiers.apply(defaults(), MissionBudgetTiers.Tier.STANDARD)
        assertNull(MissionBudgetTiers.match(limits.copy(roundTimeoutMinutes = 45)))
        assertNull(MissionBudgetTiers.match(limits.copy(maxResumeRounds = 11)))
        assertNull(MissionBudgetTiers.match(limits.copy(tokenBudget = 2_999_999L)))
        assertNull(MissionBudgetTiers.match(limits.copy(wrapUpMinutes = 9)))
    }

    @Test
    fun `stall rounds are not part of a tier`() {
        val limits = MissionBudgetTiers.apply(
            MissionLimits(stallRounds = 7, requireConfirmation = true),
            MissionBudgetTiers.Tier.LONG,
        )
        assertEquals(7, limits.stallRounds)
        assertTrue(limits.requireConfirmation)
        assertEquals(MissionBudgetTiers.Tier.LONG, MissionBudgetTiers.match(limits))
    }

    @Test
    fun `apply keeps non-budget fields untouched`() {
        val original = MissionLimits(
            maxResumeRounds = 3,
            stallRounds = 4,
            tokenBudget = 123_456L,
            requireConfirmation = true,
            roundTimeoutMinutes = 15,
            deadlineAt = 1_700_000_000_000L,
            wrapUpMinutes = 6,
        )
        val applied = MissionBudgetTiers.apply(original, MissionBudgetTiers.Tier.STANDARD)
        assertEquals(original.stallRounds, applied.stallRounds)
        assertEquals(original.requireConfirmation, applied.requireConfirmation)
        assertEquals(original.deadlineAt, applied.deadlineAt)
        assertEquals(original.maxCheckFailures, applied.maxCheckFailures)
        assertEquals(original.acceptanceChecks, applied.acceptanceChecks)
        assertEquals(original.hooks, applied.hooks)
    }

    @Test
    fun `summary clamps out-of-range values`() {
        val wild = MissionLimits(
            roundTimeoutMinutes = 10_000,
            maxResumeRounds = -3,
            tokenBudget = -1L,
            wrapUpMinutes = 999,
        )
        val summary = MissionBudgetTiers.summary(wild)
        assertEquals(MissionTimeBudget.MAX_ROUND_MIN, summary.roundTimeoutMinutes)
        assertEquals(0, summary.maxResumeRounds)
        assertEquals(0L, summary.tokenBudget)
        assertEquals(MissionTimeBudget.MAX_WRAPUP_MIN, summary.wrapUpMinutes)
    }

    @Test
    fun `every tier stays inside the clamp window`() {
        MissionBudgetTiers.Tier.entries.forEach { tier ->
            val applied = MissionBudgetTiers.apply(defaults(), tier)
            // apply 里做了夹取；档位表若写越界，这里会立刻暴露（apply 结果与档位值不一致）。
            assertEquals(tier.name, tier.roundTimeoutMinutes, applied.roundTimeoutMinutes)
            assertEquals(tier.name, tier.wrapUpMinutes, applied.wrapUpMinutes)
            assertTrue(tier.name, tier.tokenBudget > 0L)
            assertNotNull(tier.name, MissionBudgetTiers.match(applied))
        }
    }

    @Test
    fun `token budget formatting groups thousands`() {
        assertEquals("0", MissionBudgetTiers.formatTokenBudget(0))
        assertEquals("999", MissionBudgetTiers.formatTokenBudget(999))
        assertEquals("300,000", MissionBudgetTiers.formatTokenBudget(300_000))
        assertEquals("3,000,000", MissionBudgetTiers.formatTokenBudget(3_000_000))
        assertEquals("15,000,000", MissionBudgetTiers.formatTokenBudget(15_000_000))
        assertEquals("0", MissionBudgetTiers.formatTokenBudget(-5))
    }
}
