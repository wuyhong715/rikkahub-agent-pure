package me.rerere.rikkahub.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escalation ladder: 3rd identical call still runs (with a nudge), 5th and later are refused,
 * 8th is stated as a blocked turn. Pure JVM — the loop's Android plumbing is not under test here;
 * what is under test is that a repeat either executes or is refused, and that the model is told
 * which exits exist rather than just "no".
 */
class RepeatBreakerTest {

    /** priorOccurrences 0-based, i.e. streak = prior + 1: this call is the (prior + 1)th. */
    private fun at(prior: Int, refreshed: Boolean = false) =
        RepeatBreaker.decide(priorOccurrences = prior, refreshed = refreshed)

    @Test
    fun `first two calls are untouched`() {
        for (prior in 0..1) {
            val d = at(prior)
            assertEquals(RepeatGrade.NONE, d.grade)
            assertFalse(d.blocked)
            assertNull(d.reminder)
        }
    }

    @Test
    fun `third call runs but is nudged`() {
        val d = at(2)
        assertEquals(RepeatGrade.NUDGE, d.grade)
        assertFalse(d.blocked)
        assertEquals(3, d.streak)
        assertNotNull(d.reminder)
        assertTrue(d.reminder!!.startsWith("<system-reminder>"))
        assertTrue(d.reminder.contains("what new information you expect it to produce"))
    }

    @Test
    fun `fourth call still runs and is not re-nagged`() {
        val d = at(3)
        assertEquals(RepeatGrade.NONE, d.grade)
        assertFalse(d.blocked)
        assertNull(d.reminder)
        assertEquals(4, d.streak)
    }

    @Test
    fun `fifth call is refused with three exits`() {
        val d = at(4)
        assertEquals(RepeatGrade.ESCALATE, d.grade)
        assertTrue(d.blocked)
        val reminder = requireNotNull(d.reminder)
        assertTrue(reminder.contains("Falsification check"))
        assertTrue(reminder.contains("Missing input"))
        assertTrue(reminder.contains("Conclude"))
        val block = RepeatBreaker.blockFor(d.grade, "workspace_read_file", d.streak)
        assertEquals("repeat_blocked", block.code)
        assertTrue(block.recovery.contains("open_url"))
    }

    @Test
    fun `the call that crosses the escalation line is the last one that explains itself`() {
        // Streak 5 crosses the line: refused AND told why (three exits).
        val crossing = at(4)
        assertEquals(RepeatGrade.ESCALATE, crossing.grade)
        assertTrue(crossing.blocked)
        assertNotNull(crossing.reminder)

        // Streaks 6 and 7 are refused silently. Repeating the lecture every step spends tokens on a
        // model that is past reading it — the pattern is already established by the crossing call.
        for (prior in 5..6) {
            val d = at(prior)
            assertEquals(RepeatGrade.ESCALATE, d.grade)
            assertTrue(d.blocked)
            assertNull("streak ${d.streak} must not re-explain", d.reminder)
        }
    }

    @Test
    fun `eighth call is a stated blocker`() {
        val d = at(7)
        assertEquals(RepeatGrade.HARD_STOP, d.grade)
        assertTrue(d.blocked)
        assertTrue(requireNotNull(d.reminder).contains("Text only"))
        val block = RepeatBreaker.blockFor(d.grade, "take_screenshot", d.streak)
        assertEquals("repeat_hard_stop", block.code)
        // The route hints belong to the escalate rung only; at the eighth repeat the premise is the
        // problem, not the tool choice.
        assertFalse(block.recovery.contains("open_url"))
    }

    @Test
    fun `tenth call is still refused but no longer lectured`() {
        val d = at(9)
        assertEquals(RepeatGrade.HARD_STOP, d.grade)
        assertTrue(d.blocked)
        assertNull(d.reminder)
    }

    @Test
    fun `freshness bypass suppresses every rung`() {
        // A real-time reader re-read after its TTL is a refresh, not a loop: even at streak 9 it
        // runs, because refusing it hands the model a stale value instead of the fresh one it asked
        // for.
        for (prior in listOf(2, 4, 7, 9)) {
            val d = at(prior, refreshed = true)
            assertEquals(RepeatGrade.NONE, d.grade)
            assertFalse(d.blocked)
            assertNull(d.reminder)
            assertTrue(d.refreshed)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `thresholds must stay ordered`() {
        RepeatBreaker.decide(priorOccurrences = 0, nudgeAt = 6, escalateAt = 4)
    }
}
