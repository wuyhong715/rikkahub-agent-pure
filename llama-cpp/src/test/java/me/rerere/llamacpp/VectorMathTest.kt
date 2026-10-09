package me.rerere.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic an embedding index is built on. Worth its own tests even though it looks
 * trivial: a wrong normalisation does not fail loudly, it silently makes every similarity
 * score wrong, and the two ways to get it wrong here - dividing by a zero norm, and comparing
 * vectors of different widths after a model swap - both have a cheapest-possible place to be
 * caught, which is this file.
 */
class VectorMathTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 1e-5f) {
        assertTrue("expected $expected but was $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }

    private fun norm(vector: FloatArray): Float =
        kotlin.math.sqrt(vector.sumOf { it.toDouble() * it.toDouble() }).toFloat()

    @Test
    fun `normalize returns a unit vector`() {
        val normalized = VectorMath.l2Normalize(floatArrayOf(3f, 4f))
        assertClose(1f, norm(normalized))
        assertClose(0.6f, normalized[0])
        assertClose(0.8f, normalized[1])
    }

    @Test
    fun `normalize keeps direction`() {
        val original = floatArrayOf(1f, -2f, 3f, 0.5f)
        val normalized = VectorMath.l2Normalize(original)
        // Ratios between components must survive the scaling.
        assertClose(original[1] / original[0], normalized[1] / normalized[0])
        assertClose(original[2] / original[0], normalized[2] / normalized[0])
    }

    @Test
    fun `normalize does not divide by a zero norm`() {
        val normalized = VectorMath.l2Normalize(FloatArray(4))
        assertEquals(4, normalized.size)
        normalized.forEach { assertClose(0f, it) }
        normalized.forEach { assertTrue("must not become NaN", it.isFinite()) }
    }

    @Test
    fun `normalize of an empty vector is empty`() {
        assertEquals(0, VectorMath.l2Normalize(FloatArray(0)).size)
    }

    @Test
    fun `cosine of a vector with itself is one`() {
        val vector = floatArrayOf(0.5f, -1.5f, 2f, 0f)
        assertClose(1f, VectorMath.cosine(vector, vector))
    }

    @Test
    fun `cosine ignores magnitude`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(2f, 4f, 6f)
        assertClose(1f, VectorMath.cosine(a, b))
        assertClose(1f, VectorMath.cosine(a, FloatArray(3) { b[it] * -3f }) * -1f)
    }

    @Test
    fun `cosine of orthogonal vectors is zero`() {
        assertClose(0f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
    }

    @Test
    fun `cosine with a zero vector is zero rather than NaN`() {
        val score = VectorMath.cosine(floatArrayOf(1f, 2f), FloatArray(2))
        assertClose(0f, score)
        assertTrue(score.isFinite())
    }

    @Test
    fun `cosine rejects vectors of different widths`() {
        // A stale index built against a model that has since been swapped produces exactly
        // this, and a silently truncated comparison would look like a plausible score.
        assertThrows(IllegalArgumentException::class.java) {
            VectorMath.cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 2f))
        }
    }

    @Test
    fun `dot rejects vectors of different widths`() {
        assertThrows(IllegalArgumentException::class.java) {
            VectorMath.dot(floatArrayOf(1f), floatArrayOf(1f, 2f))
        }
    }

    @Test
    fun `dot of a normalized vector with itself is one`() {
        val normalized = VectorMath.l2Normalize(floatArrayOf(7f, -3f, 11f))
        assertClose(1f, VectorMath.dot(normalized, normalized), tolerance = 1e-4f)
    }
}
