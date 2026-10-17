package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecodeRulesTest {

    @Test
    fun `a clip shorter than the minimum interval still gets one frame`() {
        assertEquals(listOf(0L), VideoDecodeRules.frameTimesMs(1_500))
    }

    @Test
    fun `a short clip is sampled every two seconds`() {
        assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L), VideoDecodeRules.frameTimesMs(10_000))
    }

    @Test
    fun `the cap is never exceeded`() {
        val times = VideoDecodeRules.frameTimesMs(600_000)
        assertTrue(times.size <= VideoDecodeRules.MAX_FRAMES_PER_FILE)
    }

    @Test
    fun `the interval stretches so the frames cover the whole clip`() {
        // Ten minutes, twelve frames: the last one has to reach into the final minute, not stop
        // in the first thirty seconds.
        val times = VideoDecodeRules.frameTimesMs(600_000, maxFrames = 12)
        assertTrue(times.last() >= 540_000)
        assertTrue(times.all { it < 600_000 })
        // Evenly spaced, so "near the moment" is bounded by the interval everywhere.
        val gaps = times.zipWithNext { a, b -> b - a }.distinct()
        assertTrue(gaps.size <= 2)
    }

    @Test
    fun `a clip of unknown length is not sampled`() {
        assertTrue(VideoDecodeRules.frameTimesMs(0).isEmpty())
        assertTrue(VideoDecodeRules.frameTimesMs(-1).isEmpty())
    }

    @Test
    fun `frames are reduced to something the encoder wants`() {
        assertEquals(4, VideoDecodeRules.sampleSizeFor(3840, 2160))
        assertEquals(1, VideoDecodeRules.sampleSizeFor(800, 600))
    }

    @Test
    fun `labels carry the second a hit came from`() {
        assertEquals("Video 0:05 · clip.mp4", MediaLabels.frame("clip.mp4", 5_000))
        assertEquals("Audio 1:05–2:20 · talk.m4a", MediaLabels.audio("talk.m4a", 65_000, 140_000))
    }

    @Test
    fun `clocks read as minutes and hours`() {
        assertEquals("0:00", MediaLabels.clock(0))
        assertEquals("0:09", MediaLabels.clock(9_000))
        assertEquals("1:00", MediaLabels.clock(60_000))
        assertEquals("59:59", MediaLabels.clock(3_599_000))
        assertEquals("1:00:00", MediaLabels.clock(3_600_000))
        assertEquals("0:00", MediaLabels.clock(-1))
    }
}
