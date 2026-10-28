package me.rerere.rikkahub.runtime.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pruner runs on every request built from a compacted conversation, so its two hard
 * requirements are: it must be deterministic (a size-dependent rule would re-render the prefix on
 * every turn and bust the provider's cache), and it must tell the model that something was cut,
 * with a way to get it back.
 */
class ToolOutputPrunerTest {

    private fun output(index: Int, tokens: Int, text: String = "x".repeat(tokens * 4)) =
        PrunableToolOutput(index = index, tokens = tokens, text = text)

    @Test
    fun `only results above the cap are pruned`() {
        val plan = ToolOutputPruner.plan(
            listOf(
                output(0, 500),
                output(1, 20_000),
                output(2, ToolOutputPruner.CAP_TOKENS), // exactly at the cap: untouched
            )
        )
        assertTrue(plan.none { it.index == 0 })
        assertTrue(plan.any { it.index == 1 })
        assertTrue(plan.none { it.index == 2 })
        assertTrue(plan.all { it.reclaimedTokens > 0 })
    }

    @Test
    fun `the replacement keeps the head, marks the elision and states the recovery route`() {
        val plan = ToolOutputPruner.plan(listOf(output(0, 20_000, "a".repeat(80_000))))
        val replacement = plan.single().replacement

        assertTrue(replacement.startsWith("[This earlier tool result was shortened"))
        assertTrue("model must be told it can re-run the tool", replacement.contains("Run the tool again"))
        assertTrue(replacement.contains("a"))
        assertTrue("middle must carry the elision marker", replacement.contains("characters elided"))
        assertTrue(replacement.length < 80_000)
    }

    @Test
    fun `planning is deterministic`() {
        val candidates = listOf(output(0, 500), output(1, 20_000), output(2, 50_000))
        assertEquals(ToolOutputPruner.plan(candidates), ToolOutputPruner.plan(candidates))
    }

    @Test
    fun `nonsense thresholds prune nothing`() {
        val big = output(0, 20_000)
        assertTrue(ToolOutputPruner.plan(emptyList()).isEmpty())
        assertTrue(ToolOutputPruner.plan(listOf(big), capTokens = 0).isEmpty())
        assertTrue(
            "a target at or above the cap would loop forever turning big results slightly smaller",
            ToolOutputPruner.plan(listOf(big), capTokens = 100, targetTokens = 200).isEmpty(),
        )
    }

    @Test
    fun `the replacement is much smaller than the original`() {
        val plan = ToolOutputPruner.plan(listOf(output(0, 50_000, "b".repeat(200_000))))
        val reclaimed = plan.single().reclaimedTokens
        assertTrue("expected most of the output to be reclaimed, got $reclaimed", reclaimed > 30_000)
        assertFalse(plan.single().replacement.isEmpty())
    }
}
