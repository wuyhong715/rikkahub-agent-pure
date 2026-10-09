package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorSearchRulesTest {

    // cos against (1, 0): a = 1.0, b = cos(45°), c = 0.0.
    private val query = floatArrayOf(1f, 0f)
    private val a = "a" to floatArrayOf(1f, 0f)
    private val b = "b" to floatArrayOf(1f, 1f)
    private val c = "c" to floatArrayOf(0f, 1f)

    @Test
    fun `ranks by similarity, best first`() {
        val result = VectorSearchRules.rank(query, listOf(c, a, b), limit = 8)
        assertEquals(listOf("a", "b", "c"), result.hits.map { it.item })
        assertEquals(0, result.skipped)
        assertTrue(result.hits[0].score > result.hits[1].score)
    }

    @Test
    fun `respects the limit`() {
        val result = VectorSearchRules.rank(query, listOf(a, b, c), limit = 2)
        assertEquals(listOf("a", "b"), result.hits.map { it.item })
    }

    @Test
    fun `skips vectors of the wrong width and says so`() {
        // A model swap leaves older, differently-sized vectors in the table until the re-index
        // finishes. Search must survive that window and report it, not throw or silently return
        // a short list with no explanation.
        val stale = "stale" to FloatArray(4)
        val result = VectorSearchRules.rank(query, listOf(a, stale), limit = 8)
        assertEquals(listOf("a"), result.hits.map { it.item })
        assertEquals(1, result.skipped)
    }

    @Test
    fun `a relative floor cuts the tail but never the best hit`() {
        var result = VectorSearchRules.rank(query, listOf(a, b, c), limit = 8, relativeFloor = 0.6f)
        assertEquals(listOf("a", "b"), result.hits.map { it.item })

        result = VectorSearchRules.rank(query, listOf(a, b, c), limit = 8, relativeFloor = 0.99f)
        assertEquals(listOf("a"), result.hits.map { it.item })
    }

    @Test
    fun `an absolute threshold would not be expressible here, on purpose`() {
        // Documents why the knob is relative: built from EmbeddingGemma 2's measured scores,
        // where an unrelated pair still reaches 0.78, "keep hits above 0.8" would throw away
        // the true best match for most queries.
        val nearlyUseless = "x" to floatArrayOf(0.78f, 1f)
        val result = VectorSearchRules.rank(query, listOf(nearlyUseless), limit = 8, relativeFloor = 0.99f)
        assertEquals(listOf("x"), result.hits.map { it.item })
    }

    @Test
    fun `no candidates gives an empty result`() {
        val result = VectorSearchRules.rank<String>(query, emptyList(), limit = 8)
        assertEquals(0, result.hits.size)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `a non-positive limit is a caller bug, not a valid query`() {
        assertThrows(IllegalArgumentException::class.java) {
            VectorSearchRules.rank(query, listOf(a), limit = 0)
        }
    }

    @Test
    fun `a floor outside zero-to-one is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            VectorSearchRules.rank(query, listOf(a), limit = 8, relativeFloor = 1.5f)
        }
    }
}
