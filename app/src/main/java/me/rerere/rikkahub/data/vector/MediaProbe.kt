package me.rerere.rikkahub.data.vector

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * The two questions the index asks about a media file before it spends anything on it.
 *
 * Both are answered from the container's metadata, without decoding: how long the file is (which
 * decides whether it is worth a row at all) and whether it carries a sound track (which decides
 * whether the audio path is worth opening). Doing either by "just decode it and see" is how a
 * background round ends up decoding an hour of video to discover it has no audio.
 */
object MediaProbe {

    private const val TAG = "MediaProbe"

    /** Duration in milliseconds, or null when the container cannot say. */
    fun durationMs(file: File): Long? = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        } finally {
            runCatching { retriever.release() }
        }
    } catch (e: Exception) {
        Log.d(TAG, "cannot read the duration of '${file.name}': ${e.message}")
        null
    }

    /** True when the file has at least one audio track the platform can decode. */
    fun hasAudioTrack(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
        } catch (e: Exception) {
            Log.d(TAG, "cannot inspect the tracks of '${file.name}': ${e.message}")
            false
        } finally {
            runCatching { extractor.release() }
        }
    }
}
