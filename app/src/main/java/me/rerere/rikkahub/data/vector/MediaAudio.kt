package me.rerere.rikkahub.data.vector

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a recording into the WAV segments the embedding engine takes.
 *
 * The shape of this file is a consequence of one decision: *stream*, never decode to memory. Ten
 * minutes of 48 kHz stereo is 115 MB of PCM, and a library round that allocated that per file would
 * be killed by the OS long before it finished. So decode, downmix and resample happen buffer by
 * buffer, and only the segment being built is held.
 *
 * The arithmetic lives in [AudioDecodeRules] and [StreamingResampler], which are pure and tested;
 * what is left here is the `MediaCodec` dance and the bookkeeping of which segment a sample belongs
 * to. Anything unclear about the numbers should be answered by those two, not by this file.
 */
object MediaAudio {

    private const val TAG = "MediaAudio"

    /**
     * Decodes [file] and returns its segments, or null when it has no audio track or cannot be read.
     *
     * [maxSegments] bounds the work rather than the file: a two-hour recording would otherwise be a
     * hundred embedding calls in one round. The tail is dropped when the cap is reached, and the
     * caller's budget is what stops the file from being reconsidered next round.
     */
    suspend fun read(
        file: File,
        durationMs: Long,
        maxSegments: Int,
        shouldStop: () -> Boolean = { false },
    ): List<Pair<AudioDecodeRules.Segment, ByteArray>> = withContext(Dispatchers.IO) {
        val plan = AudioDecodeRules.segments(durationMs).take(maxSegments.coerceAtLeast(0))
        if (plan.isEmpty()) return@withContext emptyList()

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var result: List<Pair<AudioDecodeRules.Segment, ByteArray>> = emptyList()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return@withContext emptyList()

            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext emptyList()
            // 16-bit is requested explicitly: some decoders default to float on newer platforms and
            // the resampler below is written for integers.
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            }
            var sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (sourceRate <= 0 || sourceChannels <= 0) return@withContext emptyList()

            val codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            decoder = codec

            var resampler = StreamingResampler(fromHz = sourceRate)
            val sink = SegmentSink(plan)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            // A decoder that never sets end-of-stream would otherwise spin here: after the input is
            // exhausted, a second of "nothing yet" is conclusive enough for a background round.
            var idleOutputs = 0

            while (!outputDone && !shouldStop()) {
                ensureActive()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: ByteBuffer.allocate(0)
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone && ++idleOutputs > 100) outputDone = true
                    }

                    // Some decoders emit a different rate or channel count than the track declared
                    // (HE-AAC doubles its rate, for one). Following the output format is the only
                    // way the samples added to a segment stay the length the segment claims.
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        val rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (rate > 0 && rate != sourceRate) {
                            sourceRate = rate
                            resampler = StreamingResampler(fromHz = rate)
                        }
                        if (channels > 0) sourceChannels = channels
                    }
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            idleOutputs = 0
                            val pcm = buffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                            val samples = ShortArray(pcm.remaining())
                            pcm.get(samples)
                            val mono = AudioDecodeRules.downmixToMono(samples, sourceChannels)
                            sink.append(resampler.process(mono))
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }

            sink.finish()
            result = sink.result()
        } catch (e: Exception) {
            // What was produced before the failure is still returned: a half-readable recording is
            // more useful indexed than absent, and the rows that did make it are complete segments.
            Log.d(TAG, "cannot decode '${file.name}': ${e.message}")
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
        result
    }

    /**
     * Slices the resampled stream into the planned segments as it arrives.
     *
     * A segment is only emitted when it is complete, so a decoder that dies halfway leaves no
     * half-row behind; whatever was open at that point is dropped with it.
     */
    private class SegmentSink(private val plan: List<AudioDecodeRules.Segment>) {
        private val out = ArrayList<Pair<AudioDecodeRules.Segment, ByteArray>>(plan.size)
        private var index = 0
        private var buffer = ShortArray(0)
        private var filled = 0

        fun append(samples: ShortArray) {
            var offset = 0
            while (offset < samples.size && index < plan.size) {
                if (buffer.isEmpty()) {
                    buffer = ShortArray(AudioDecodeRules.samplesForMs(plan[index].durationMs))
                    filled = 0
                }
                val take = minOf(buffer.size - filled, samples.size - offset)
                System.arraycopy(samples, offset, buffer, filled, take)
                filled += take
                offset += take
                if (filled == buffer.size) complete()
            }
        }

        /** Emits a short last segment rather than discarding the tail of the recording. */
        fun finish() {
            if (index < plan.size && filled > 0 && filled.toLong() * 1000 / AudioDecodeRules.SAMPLE_RATE >=
                AudioDecodeRules.MIN_SEGMENT_MS
            ) {
                complete()
            }
        }

        fun result(): List<Pair<AudioDecodeRules.Segment, ByteArray>> = out

        private fun complete() {
            val segment = plan[index]
            out += segment to AudioDecodeRules.wavBytes(buffer.copyOf(filled))
            index++
            buffer = ShortArray(0)
            filled = 0
        }
    }
}
