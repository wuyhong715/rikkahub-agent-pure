package me.rerere.rikkahub.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compaction is the one operation that spends a model call to throw information away, and until now
 * the only evidence of it was a free-text log line on success — nothing recorded the no-op or the
 * failure. These tests pin the two things a bug report depends on: the arithmetic is honest, and
 * the rendering keeps every field present (an absent `reclaimed` would read as zero).
 */
class CompactionDiagnosticsTest {

    private fun created(before: Int, after: Int) = CompactionEvent(
        conversationId = "c1",
        trigger = CompactionTrigger.AUTO,
        status = CompactionStatus.CREATED,
        tokensBefore = before,
        tokensAfter = after,
        summaryChars = 3_100,
        modelId = "m1",
    )

    @Test
    fun `reclaimed tokens are measured, never negative`() {
        assertEquals(78_000, created(120_000, 42_000).reclaimedTokens)
        assertEquals(0, created(10, 20).reclaimedTokens)
        assertNull(CompactionEvent("c1", CompactionTrigger.MANUAL, CompactionStatus.FAILED, 100, null).reclaimedTokens)
    }

    @Test
    fun `the rendered line is stable and complete`() {
        assertEquals(
            "compaction conv=c1 trigger=auto status=created tokens=120000->42000 reclaimed=78000 " +
                "summary_chars=3100 model=m1",
            created(120_000, 42_000).render(),
        )
        // Unknowns render as '-' rather than vanishing.
        val failed = CompactionEvent("c1", CompactionTrigger.MANUAL, CompactionStatus.FAILED).render()
        assertTrue(failed.contains("tokens=-->-"))
        assertTrue(failed.contains("reclaimed=-"))
        assertTrue(failed.contains("model=-"))
    }

    @Test
    fun `the per-conversation history is bounded and scoped`() {
        CompactionDiagnostics.clear("c1")
        repeat(CompactionDiagnostics.MAX_PER_CONVERSATION + 3) {
            CompactionDiagnostics.record(created(it, it))
        }

        val recent = CompactionDiagnostics.recent("c1")
        assertEquals(CompactionDiagnostics.MAX_PER_CONVERSATION, recent.size)
        assertEquals(CompactionDiagnostics.MAX_PER_CONVERSATION + 2, recent.last().tokensBefore)
        assertTrue(CompactionDiagnostics.recent("c2").isEmpty())

        CompactionDiagnostics.clear("c1")
        assertTrue(CompactionDiagnostics.recent("c1").isEmpty())
    }
}
