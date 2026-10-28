package me.rerere.rikkahub.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anchors exist to make one promise: after a compaction, the user's own wording is still in the
 * request. These tests pin the selection rules that keep the promise affordable — the first message
 * (the original task) survives if it fits, the most recent instructions win the remaining budget,
 * and an oversized message is skipped rather than spending the whole allowance.
 */
class CompactionAnchorsTest {

    private fun candidate(index: Int, text: String = "m$index", tokens: Int = 100) =
        AnchorCandidate(index = index, text = text, tokens = tokens)

    @Test
    fun `nothing selected without candidates or budget`() {
        assertTrue(CompactionAnchors.select(emptyList()).isEmpty())
        assertTrue(CompactionAnchors.select(listOf(candidate(0, tokens = 1)), budgetTokens = 0).isEmpty())
    }

    @Test
    fun `the first message survives and the most recent fill the rest`() {
        val selected = CompactionAnchors.select((0 until 10).map(::candidate), budgetTokens = 350)

        assertEquals(0, selected.first().index)
        assertTrue("the latest instruction must be kept", selected.any { it.index == 9 })
        assertTrue("output is chronological", selected.map { it.index } == selected.map { it.index }.sorted())
        assertTrue(selected.sumOf { it.tokens } <= 350)
    }

    @Test
    fun `an oversized first message is skipped instead of consuming the budget`() {
        val candidates = listOf(candidate(0, "huge", 5_000)) + (1..3).map { candidate(it) }

        val selected = CompactionAnchors.select(candidates, budgetTokens = 300)

        assertTrue(selected.none { it.index == 0 })
        assertTrue(selected.isNotEmpty())
        assertTrue(selected.sumOf { it.tokens } <= 300)
    }

    @Test
    fun `a single candidate exactly at the budget still fits`() {
        assertEquals(1, CompactionAnchors.select(listOf(candidate(0, tokens = 100)), budgetTokens = 100).size)
    }

    @Test
    fun `render states the provenance and numbers the items`() {
        assertTrue(CompactionAnchors.render(emptyList()).isEmpty())

        val text = CompactionAnchors.render(
            listOf(
                candidate(0, "do not touch the DB", 10),
                candidate(3, "use tabs", 5),
            )
        )
        assertTrue(text.startsWith("[User messages retained verbatim"))
        assertTrue(text.contains("1. do not touch the DB"))
        assertTrue(text.contains("2. use tabs"))
        assertTrue(text.endsWith("use tabs"))
    }
}
