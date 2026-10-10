package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-01 — the fusion contract.
 *
 * These are the cases the chat paths depend on: exact-name behaviour must survive (RRF only
 * reorders), a tool only the vector channel found must still be reachable, the result must be a
 * total order, and the per-turn budget must count every schema it hands over.
 */
class ToolRankFusionTest {

    // ---- fuse -----------------------------------------------------------------------------

    @Test
    fun `fusion keeps an exact lexical top hit at the top`() {
        // The behaviour tool_search already had: asking for a tool by name finds that tool first,
        // whatever the vector channel thinks.
        val fused = ToolRankFusion.fuse(
            lexical = listOf("scrape_web", "search_web", "extract_links"),
            vector = listOf("extract_links", "scrape_web", "search_web"),
        )
        assertEquals("scrape_web", fused.first())
    }

    @Test
    fun `fusion promotes a tool only the vector channel found`() {
        // The point of the whole exercise: "把屏幕上的字识出来" shares no token with `read_screen`,
        // so lexical returns nothing at all and the vector channel is the only witness.
        val fused = ToolRankFusion.fuse(
            lexical = emptyList(),
            vector = listOf("read_screen_text", "take_screenshot"),
        )
        assertEquals(listOf("read_screen_text", "take_screenshot"), fused)
    }

    @Test
    fun `fusion prefers agreement between the channels over a single channel's favourite`() {
        // `b` is rank 1 lexically but unknown to the vector channel; `c` is rank 2 in both. Two
        // second places (2/62) outweigh one first place (1/61): consensus beats a single channel's
        // enthusiasm, and `x` — rank 1 to the vector channel alone — is beaten by `b` only on the
        // deterministic tie-break (lexical was read first).
        val fused = ToolRankFusion.fuse(
            lexical = listOf("b", "c"),
            vector = listOf("x", "c"),
        )
        assertEquals(listOf("c", "b", "x"), fused)
    }

    @Test
    fun `fusion of one empty list preserves the other's order`() {
        val order = listOf("a", "b", "c")
        assertEquals(order, ToolRankFusion.fuse(order, emptyList()))
        assertEquals(order, ToolRankFusion.fuse(emptyList(), order))
    }

    @Test
    fun `fusion is a total order and never duplicates a name`() {
        val fused = ToolRankFusion.fuse(
            lexical = listOf("same", "other"),
            vector = listOf("same", "other"),
        )
        assertEquals(listOf("same", "other"), fused)

        // Same score for both (rank 1 in one list each): the tie must resolve the same way every
        // run, or retrieval stops being testable.
        val tied = ToolRankFusion.fuse(listOf("zeta"), listOf("alpha"))
        assertEquals(listOf("zeta", "alpha"), tied)
        assertEquals(tied, ToolRankFusion.fuse(listOf("zeta"), listOf("alpha")))
    }

    @Test
    fun `fusing nothing yields nothing`() {
        assertTrue(ToolRankFusion.fuse(emptyList(), emptyList()).isEmpty())
    }

    // ---- rankByCosine ---------------------------------------------------------------------

    @Test
    fun `cosine ranking orders by similarity and breaks ties by name`() {
        val query = floatArrayOf(1f, 0f)
        val keys = listOf("far", "near", "tieB", "tieA")
        val vectors = listOf(
            floatArrayOf(0f, 1f),
            floatArrayOf(1f, 0f),
            floatArrayOf(1f, 0f),
            floatArrayOf(1f, 0f),
        )
        assertEquals(listOf("near", "tieA", "tieB", "far"), ToolRankFusion.rankByCosine(query, keys, vectors))
    }

    @Test
    fun `cosine ranking refuses a mismatched catalogue instead of half-answering`() {
        val ranked = ToolRankFusion.rankByCosine(
            query = floatArrayOf(1f),
            keys = listOf("only-one-entry-for-two-vectors"),
            docVectors = emptyList(),
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `cosine ranking of a width mismatch sinks the offending document`() {
        val ranked = ToolRankFusion.rankByCosine(
            query = floatArrayOf(1f, 0f),
            keys = listOf("wrong-width", "right-width"),
            docVectors = listOf(floatArrayOf(1f), floatArrayOf(1f, 0f)),
        )
        assertEquals(listOf("right-width", "wrong-width"), ranked)
    }

    // ---- selectForTurn --------------------------------------------------------------------

    @Test
    fun `turn selection puts pins first, then retrieval, without duplicates`() {
        val selected = ToolRankFusion.selectForTurn(
            fused = listOf("read_window_tree", "tap", "scroll"),
            pinned = listOf("memory_read", "read_window_tree"),
            budget = 4,
        )
        assertEquals(listOf("memory_read", "read_window_tree", "tap", "scroll"), selected)
    }

    @Test
    fun `turn selection counts pins against the budget`() {
        val selected = ToolRankFusion.selectForTurn(
            fused = listOf("a", "b", "c"),
            pinned = listOf("p1", "p2", "p3"),
            budget = 4,
        )
        // Three pins already spent three of the four slots; only one retrieved tool fits.
        assertEquals(listOf("p1", "p2", "p3", "a"), selected)
    }

    @Test
    fun `a zero budget still honours pins`() {
        // "Do not guess for me" is about the automatic half, not about stripping the tools the
        // user asked for by name.
        assertEquals(
            listOf("pinned"),
            ToolRankFusion.selectForTurn(fused = listOf("a"), pinned = listOf("pinned"), budget = 0),
        )
    }

    @Test
    fun `turn selection of nothing is nothing`() {
        assertTrue(ToolRankFusion.selectForTurn(emptyList(), emptyList()).isEmpty())
    }

    // ---- embedText ------------------------------------------------------------------------

    @Test
    fun `embed text spells the name out and keeps the summary`() {
        assertEquals(
            "take screenshot Capture the current display",
            ToolRankFusion.embedText("take_screenshot", "Capture the current display"),
        )
    }

    @Test
    fun `embed text survives a blank summary`() {
        assertEquals("read window tree", ToolRankFusion.embedText("read_window_tree", "   "))
    }
}
