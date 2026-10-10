package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDecodeRulesTest {

    @Test
    fun `an image that already fits is handed over as it is`() {
        assertEquals(1, ImageDecodeRules.sampleSizeFor(800, 600, 1600))
        assertEquals(1, ImageDecodeRules.sampleSizeFor(1600, 1600, 1600))
        assertEquals(1, ImageDecodeRules.sampleSizeFor(1440, 3168, 3168))
    }

    @Test
    fun `the longest edge decides the sample, not the shortest`() {
        assertEquals(2, ImageDecodeRules.sampleSizeFor(3200, 400, 1600))
        assertEquals(2, ImageDecodeRules.sampleSizeFor(400, 3200, 1600))
    }

    @Test
    fun `a sample is the smallest power of two that still fits`() {
        // 4000 -> 2000 -> 1000. Stopping at 2 would not fit; 4 overshoots downwards, which is the
        // intended direction, because being under the limit costs legibility and being over it
        // costs a bitmap nobody wanted.
        val sample = ImageDecodeRules.sampleSizeFor(4000, 3000, 1600)
        assertEquals(4, sample)
        assertTrue(4000 / sample <= 1600)
        // One step smaller would not have fitted, which is what makes this the *smallest* step.
        assertFalse(4000 / (sample / 2) <= 1600)
    }

    @Test
    fun `a photograph from a phone is reduced to something decodable`() {
        val sample = ImageDecodeRules.sampleSizeFor(4032, 3024)
        assertEquals(4, sample)
        assertEquals(1008, 4032 / sample)
    }

    @Test
    fun `the sample never enlarges a small image`() {
        // Upscaling a blurry screenshot does not make its text readable, and it is not free.
        assertEquals(1, ImageDecodeRules.sampleSizeFor(120, 90, 1600))
        assertEquals(1, ImageDecodeRules.sampleSizeFor(1, 1, 1600))
    }

    @Test
    fun `a degenerate size or limit is answered rather than divided by`() {
        assertEquals(1, ImageDecodeRules.sampleSizeFor(0, 0, 1600))
        assertEquals(1, ImageDecodeRules.sampleSizeFor(-1, 100, 1600))
        assertEquals(1, ImageDecodeRules.sampleSizeFor(100, -1, 1600))
        assertEquals(1, ImageDecodeRules.sampleSizeFor(4000, 3000, 0))
        assertEquals(1, ImageDecodeRules.sampleSizeFor(4000, 3000, -1600))
    }
}
