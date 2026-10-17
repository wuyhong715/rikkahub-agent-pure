package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDecodeRulesTest {

    @Test
    fun `a short recording is one segment`() {
        val segments = AudioDecodeRules.segments(12_000)
        assertEquals(1, segments.size)
        assertEquals(0L, segments.single().startMs)
        assertEquals(12_000L, segments.single().endMs)
    }

    @Test
    fun `a long recording is cut into whole segments and a remainder`() {
        val segments = AudioDecodeRules.segments(200_000, maxSegmentMs = 75_000)
        assertEquals(3, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(75_000L, segments[0].endMs)
        assertEquals(75_000L, segments[1].startMs)
        assertEquals(150_000L, segments[2].startMs)
        assertEquals(200_000L, segments[2].endMs)
        // Gapless: a segment starts exactly where the previous one ended.
        segments.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
    }

    @Test
    fun `a tail too short to be worth a row is dropped`() {
        // 150_100 leaves a 100 ms tail, under the 250 ms floor.
        val segments = AudioDecodeRules.segments(150_100, maxSegmentMs = 75_000)
        assertEquals(2, segments.size)
        assertEquals(150_000L, segments.last().endMs)
    }

    @Test
    fun `a recording shorter than the floor produces nothing`() {
        assertTrue(AudioDecodeRules.segments(100).isEmpty())
        assertTrue(AudioDecodeRules.segments(0).isEmpty())
        assertTrue(AudioDecodeRules.segments(-5).isEmpty())
    }

    @Test
    fun `a segment never exceeds the signature it was planned for`() {
        // 2048 tokens at 25 tokens per second is about 80 seconds; the plan must stay under it.
        val segments = AudioDecodeRules.segments(600_000)
        assertTrue(segments.all { it.durationMs <= 75_000 })
    }

    @Test
    fun `stereo is averaged, not truncated`() {
        val stereo = shortArrayOf(100, 200, -100, -200)
        val mono = AudioDecodeRules.downmixToMono(stereo, channels = 2)
        assertEquals(2, mono.size)
        assertEquals(150, mono[0].toInt())
        assertEquals(-150, mono[1].toInt())
    }

    @Test
    fun `a mono buffer is left alone`() {
        val mono = shortArrayOf(1, 2, 3)
        assertEquals(mono, AudioDecodeRules.downmixToMono(mono, channels = 1))
    }

    @Test
    fun `the wav header describes exactly the bytes that follow it`() {
        val header = AudioDecodeRules.wavHeader(dataBytes = 32_000)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        // RIFF size = everything after the first 8 bytes.
        assertEquals(36 + 32_000, header.le32(4))
        // PCM, 1 channel, 16 000 Hz, 32 000 bytes per second, block align 2, 16 bits.
        assertEquals(1, header.le16(20))
        assertEquals(1, header.le16(22))
        assertEquals(16_000, header.le32(24))
        assertEquals(32_000, header.le32(28))
        assertEquals(2, header.le16(32))
        assertEquals(16, header.le16(34))
        assertEquals(32_000, header.le32(40))
    }

    @Test
    fun `wav bytes are a header followed by little-endian samples`() {
        val bytes = AudioDecodeRules.wavBytes(shortArrayOf(0x0102, -2))
        assertEquals(AudioDecodeRules.WAV_HEADER_BYTES + 4, bytes.size)
        assertEquals(0x02, bytes[44].toInt())
        assertEquals(0x01, bytes[45].toInt())
        // -2 as unsigned little-endian is 0xFFFE.
        assertEquals(0xFE, bytes[46].toInt() and 0xFF)
        assertEquals(0xFF, bytes[47].toInt() and 0xFF)
        assertEquals(4, bytes.le32(40))
    }

    @Test
    fun `resampling halves the sample rate to half the samples`() {
        val resampler = StreamingResampler(fromHz = 32_000, toHz = 16_000)
        val input = ShortArray(3_200) { 100 }
        val out = resampler.process(input)
        assertEquals(1_600, out.size)
        assertTrue(out.all { it.toInt() == 100 })
    }

    @Test
    fun `resampling across buffers matches resampling in one go`() {
        val source = ShortArray(4_800) { (it % 200).toShort() }
        val whole = StreamingResampler(fromHz = 48_000, toHz = 16_000).process(source)

        val streaming = StreamingResampler(fromHz = 48_000, toHz = 16_000)
        val parts = ArrayList<Short>()
        var offset = 0
        // Deliberately uneven buffers: a boundary at an odd sample is where phase is lost.
        for (size in intArrayOf(1, 999, 1_000, 1_337, 1_463)) {
            if (offset >= source.size) break
            val end = minOf(offset + size, source.size)
            parts += streaming.process(source.copyOfRange(offset, end)).toList()
            offset = end
        }

        assertEquals(whole.size, parts.size)
        assertEquals(whole.toList(), parts)
    }

    @Test
    fun `a source at the target rate is passed through untouched`() {
        val input = shortArrayOf(5, 6, 7)
        assertEquals(input, StreamingResampler(16_000).process(input))
    }

    @Test
    fun `samples for a millisecond count is exact at the edges`() {
        assertEquals(0, AudioDecodeRules.samplesForMs(0))
        assertEquals(16, AudioDecodeRules.samplesForMs(1))
        assertEquals(16_000, AudioDecodeRules.samplesForMs(1_000))
        assertEquals(AudioDecodeRules.samplesForMs(75_000), 1_200_000)
    }

    private fun ByteArray.le16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.le32(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
}
