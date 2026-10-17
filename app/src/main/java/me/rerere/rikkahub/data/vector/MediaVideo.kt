package me.rerere.rikkahub.data.vector

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Reads a video into the frames the vision encoder takes.
 *
 * `MediaMetadataRetriever` rather than a decoder pipeline: the only thing wanted is a dozen
 * thumbnails, and `getFrameAtTime` answers exactly that from the container's index without starting
 * a codec for every seek. `OPTION_CLOSEST` rather than `OPTION_CLOSEST_SYNC` because the interesting
 * moment is rarely on a keyframe - a phone video can put them ten seconds apart - and a hit that
 * jumps to a visibly different scene is worse than the extra decode.
 *
 * Every frame is decoded, scaled, compressed and released one at a time: twelve 4K bitmaps at once
 * would be 400 MB of heap for a result that is thrown away as soon as it is a JPEG.
 */
object MediaVideo {

    private const val TAG = "MediaVideo"

    /** One sampled frame: when it is, and the JPEG bytes handed to the engine. */
    data class Frame(val atMs: Long, val jpeg: ByteArray)

    /**
     * Samples [file] and returns its frames, or an empty list when it cannot be read.
     *
     * [durationMs] comes from the caller's one metadata read, so this does not seek blindly at
     * timestamps past the end. Frames are dropped rather than the whole file when one of them fails
     * to decode - a partially readable video is still searchable.
     */
    suspend fun read(
        file: File,
        durationMs: Long,
        maxFrames: Int = VideoDecodeRules.MAX_FRAMES_PER_FILE,
        shouldStop: () -> Boolean = { false },
    ): List<Frame> = withContext(Dispatchers.IO) {
        val times = VideoDecodeRules.frameTimesMs(durationMs, maxFrames)
        if (times.isEmpty()) return@withContext emptyList()

        val retriever = MediaMetadataRetriever()
        val frames = ArrayList<Frame>(times.size)
        try {
            retriever.setDataSource(file.absolutePath)
            for (at in times) {
                ensureActive()
                if (shouldStop()) break
                val bitmap = try {
                    retriever.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (e: Exception) {
                    Log.d(TAG, "cannot read frame at ${at}ms of '${file.name}': ${e.message}")
                    null
                } ?: continue
                val jpeg = runCatching { toJpeg(bitmap) }.getOrNull()
                bitmap.recycle()
                if (jpeg != null) frames += Frame(atMs = at, jpeg = jpeg)
            }
        } catch (e: Exception) {
            Log.d(TAG, "cannot read '${file.name}': ${e.message}")
            emptyList<Frame>()
        } finally {
            runCatching { retriever.release() }
        }
        frames
    }

    /**
     * Scales down to [VideoDecodeRules.MAX_EDGE_PX] along the longest edge and compresses to JPEG.
     *
     * Scaled by the decoder's own sample size first where that is enough, and by a re-draw only for
     * the remainder: `createScaledBitmap` on a 4K frame allocates a second 4K bitmap, which is the
     * cost this is trying to avoid.
     */
    private fun toJpeg(source: Bitmap): ByteArray {
        val longest = maxOf(source.width, source.height)
        val scaled = if (longest <= VideoDecodeRules.MAX_EDGE_PX) {
            source
        } else {
            val ratio = VideoDecodeRules.MAX_EDGE_PX.toFloat() / longest
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, VideoDecodeRules.JPEG_QUALITY, out)
        if (scaled !== source) scaled.recycle()
        return out.toByteArray()
    }
}
