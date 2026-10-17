package me.rerere.rikkahub.data.vector

/**
 * The arithmetic of turning a decoded clip into what the embedding engine takes.
 *
 * The model's requirements are narrow and stated in its own card: mono, 16 kHz, and at most 25
 * tokens per second of sound inside the loaded signature. Three consequences follow, and all three
 * are integer arithmetic that is easy to get convincingly wrong:
 *
 *  * a long recording has to be cut into segments that fit the signature, and the cut is what every
 *    hit is later labelled with, so the same plan has to be reproducible;
 *  * a phone's decoder hands over 44.1 or 48 kHz stereo, so the samples have to be downmixed and
 *    resampled, and doing that per buffer means carrying phase across buffer boundaries;
 *  * the engine is handed bytes, and the container is WAV, so a 44-byte header is written by hand.
 *
 * All of it is pure, so all of it is tested. The Android decode loop that feeds it is
 * [MediaAudio], and it holds no arithmetic of its own.
 */
object AudioDecodeRules {

    /** What the model card asks for: mono 16 kHz. */
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    /** RIFF header + `fmt ` + `data` chunk headers. */
    const val WAV_HEADER_BYTES = 44

    /**
     * How much sound one segment may hold.
     *
     * 75 seconds is a compromise between two costs: the model charges 25 tokens per second, so a
     * segment has to stay under the loaded signature (2048 tokens is about 80 seconds), and every
     * segment is one embedding call, which on the CPU is what makes a long recording expensive.
     * Shorter would run out of context for a conversation; longer would not fit.
     */
    const val MAX_SEGMENT_MS = 75_000

    /**
     * A segment shorter than this is not worth a row.
     *
     * The tail of a recording cut mid-sentence produces one of these, and a vector for two seconds
     * of room tone is a row that matches random queries. Bounded at a quarter of a second so a
     * genuinely short voice note still indexes.
     */
    const val MIN_SEGMENT_MS = 250

    /** One slice of a recording, in the source's own clock. */
    data class Segment(val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
    }

    /**
     * How a recording of [durationMs] is cut.
     *
     * Non-overlapping and from the start: an overlap would double the embedding cost of a
     * recording for a benefit the label cannot express (two rows claiming the same second), and a
     * tail shorter than [MIN_SEGMENT_MS] is dropped rather than indexed.
     */
    fun segments(durationMs: Long, maxSegmentMs: Int = MAX_SEGMENT_MS): List<Segment> {
        if (durationMs <= 0) return emptyList()
        val limit = maxSegmentMs.coerceAtLeast(1).toLong()
        val result = ArrayList<Segment>()
        var start = 0L
        while (start < durationMs) {
            val end = minOf(start + limit, durationMs)
            if (end - start >= MIN_SEGMENT_MS) result += Segment(start, end)
            start = end
        }
        return result
    }

    /** Samples one millisecond of 16 kHz mono holds. Rounded down, like the decoder. */
    fun samplesForMs(millis: Long): Int = (millis * SAMPLE_RATE / 1000).toInt()

    /**
     * A 16-bit PCM WAV container around [dataBytes] of samples.
     *
     * Written by hand rather than by a library because this is the entire format the engine needs:
     * the model card's own example feeds it a `.wav` file, and there is no encoder in the app that
     * would produce one.
     */
    fun wavHeader(
        dataBytes: Int,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        bitsPerSample: Int = BITS_PER_SAMPLE,
    ): ByteArray {
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val header = ByteArray(WAV_HEADER_BYTES)
        header.putAscii(0, "RIFF")
        header.putLe32(4, 36 + dataBytes)
        header.putAscii(8, "WAVE")
        header.putAscii(12, "fmt ")
        header.putLe32(16, 16)
        header.putLe16(20, 1) // PCM
        header.putLe16(22, channels)
        header.putLe32(24, sampleRate)
        header.putLe32(28, byteRate)
        header.putLe16(32, blockAlign)
        header.putLe16(34, bitsPerSample)
        header.putAscii(36, "data")
        header.putLe32(40, dataBytes)
        return header
    }

    /** [wavHeader] followed by little-endian copies of [samples]. */
    fun wavBytes(samples: ShortArray): ByteArray {
        val body = ByteArray(samples.size * BYTES_PER_SAMPLE)
        for (i in samples.indices) {
            val value = samples[i].toInt()
            body[i * 2] = (value and 0xFF).toByte()
            body[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return wavHeader(body.size) + body
    }

    /**
     * Averages [channels] interleaved samples down to mono.
     *
     * Averaging rather than taking the first channel: a stereo recording of a conversation puts a
     * different speaker in each channel often enough that dropping one loses half the audio, and
     * the model has one ear.
     */
    fun downmixToMono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val mono = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) sum += interleaved[frame * channels + channel]
            mono[frame] = (sum / channels).toShort()
        }
        return mono
    }

    private fun ByteArray.putAscii(offset: Int, value: String) {
        for (i in value.indices) this[offset + i] = value[i].code.toByte()
    }

    private fun ByteArray.putLe16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putLe32(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
        this[offset + 2] = ((value shr 16) and 0xFF).toByte()
        this[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}

/**
 * Streaming linear resampler, 16-bit mono in and out.
 *
 * Stateful on purpose: a decoder hands over buffers of arbitrary length, and resampling each one
 * independently would put a discontinuity - and a wrong sample count - at every boundary. The
 * carried phase plus the last sample of the previous buffer is what makes the concatenated output
 * identical to resampling the whole clip at once.
 *
 * Linear interpolation rather than a windowed sinc: the consumer is a model that hears 40 ms of
 * sound per token, so the passband detail a proper filter preserves is below what it can represent,
 * while the filter's cost would be paid per sample on a phone.
 */
class StreamingResampler(
    private val fromHz: Int,
    private val toHz: Int = AudioDecodeRules.SAMPLE_RATE,
) {
    init {
        require(fromHz > 0) { "the source rate must be positive" }
        require(toHz > 0) { "the target rate must be positive" }
    }

    private val step: Double = fromHz.toDouble() / toHz.toDouble()
    private var position: Double = 0.0
    private var carry: Short = 0
    private var started = false

    /** Resamples the next buffer and returns what it produces. */
    fun process(input: ShortArray): ShortArray {
        if (fromHz == toHz) return input
        if (input.isEmpty()) return ShortArray(0)

        // A one-sample prefix makes the first output of a buffer able to interpolate against the
        // last sample of the previous one; on the very first buffer it is a copy of the first
        // sample, which interpolates to itself.
        val virtual = ShortArray(input.size + 1)
        virtual[0] = if (started) carry else input[0]
        input.copyInto(virtual, 1)

        val out = ArrayList<Short>((input.size / step).toInt() + 2)
        var p = position
        while (true) {
            val index = p.toInt()
            if (index + 1 >= virtual.size) break
            val fraction = p - index
            val first = virtual[index].toInt()
            val second = virtual[index + 1].toInt()
            out += (first + (second - first) * fraction).toInt().toShort()
            p += step
        }

        carry = virtual[virtual.size - 1]
        started = true
        // Re-express the position in the next buffer's coordinates: its index 0 is this buffer's
        // last sample.
        position = p - (virtual.size - 1)
        if (position < 0) position = 0.0
        return out.toShortArray()
    }
}
