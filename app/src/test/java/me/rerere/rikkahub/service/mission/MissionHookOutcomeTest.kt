package me.rerere.rikkahub.service.mission

import me.rerere.rikkahub.data.mission.MissionHook
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-18 — a hook's verdict vs. its harness.
 *
 * The supervisor's contract is a soft failure: an infrastructure problem (no workspace, shell not
 * ready, process spawn failed, transient timeout) is logged and the round continues. Only a command
 * that actually ran and returned non-zero is a verdict on the work. `failed` used to be
 * `!result.ok`, which is true for an infrastructure error too — so one environment hiccup blocked
 * ROUND_END / BEFORE_DONE and parked a mission that was doing fine.
 */
class MissionHookOutcomeTest {

    private fun outcome(result: HookResult) = MissionHookOutcome(
        hook = MissionHook(command = "pytest -q"),
        result = result,
    )

    @Test
    fun `an infrastructure error is not a verdict`() {
        val o = outcome(HookResult(error = "shell not ready"))
        assertFalse(o.failed)
        assertFalse(o.blocking)
    }

    @Test
    fun `a real non-zero exit is a verdict`() {
        val o = outcome(HookResult(exitCode = 1, stderr = "2 failed"))
        assertTrue(o.failed)
        assertTrue(o.blocking)
    }

    @Test
    fun `a timeout is a verdict`() {
        assertTrue(outcome(HookResult(timedOut = true)).failed)
    }

    @Test
    fun `a clean exit is not a failure`() {
        assertFalse(outcome(HookResult(exitCode = 0, stdout = "ok")).failed)
    }

    @Test
    fun `a skipped hook is neither failed nor blocking`() {
        val o = MissionHookOutcome(hook = MissionHook(), result = null, skippedReason = "任务没有工作区")
        assertFalse(o.failed)
        assertFalse(o.blocking)
    }
}
