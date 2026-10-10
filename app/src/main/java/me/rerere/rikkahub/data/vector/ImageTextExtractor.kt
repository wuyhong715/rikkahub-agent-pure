package me.rerere.rikkahub.data.vector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.File

private const val TAG = "ImageText"

/**
 * Reads the text out of one image file, on device.
 *
 * This is the leaf of the file library's image support. Everything around it - which files are
 * candidates, what a round may spend on one, what an image with no text in it becomes - lives in
 * [WorkspaceLibraryRules], because that is the part that can be reasoned about and tested without a
 * phone. What is left here is the part that genuinely needs one.
 *
 * Three decisions worth their words:
 *
 *  - **Downscaled before recognition.** The arithmetic is in [ImageDecodeRules]; the reason is that
 *    a full-size decode of a modern camera image is tens of megabytes of heap, and the recognizer
 *    gains nothing from pixels it cannot read anyway.
 *  - **Rotation applied, not ignored.** `BitmapFactory` does not apply the EXIF orientation, so a
 *    photograph taken in portrait arrives sideways and recognises as nothing at all. The framework
 *    `ExifInterface` reads the orientation for JPEGs - the format a camera writes - without pulling
 *    in another dependency. Formats that have no orientation to apply report none, and are used as
 *    stored.
 *  - **Recognition rather than description.** The recognizer reads the characters that are in the
 *    image; it does not decide what the image is *of*. That is the whole reason this is cheap
 *    enough to run per file: a screenshot of a stack trace becomes the words of that stack trace,
 *    which is exactly what a search for it needs, and nothing has to be uploaded to get them.
 *
 * The recognizer is created once and reused: ML Kit loads its model on first use, and building a
 * client per image would pay that cost on every file of a library.
 */
class ImageTextExtractor(private val maxEdgePx: Int = ImageDecodeRules.DEFAULT_MAX_EDGE_PX) {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * The text in [file], or "" when there is none.
     *
     * An unreadable file, an image with no text in it, and a recognition that failed are all the
     * same answer on purpose: the caller has one question - "is there anything here to index" - and
     * exactly one thing to do when the answer is no.
     */
    suspend fun extract(file: File): String {
        val bitmap = decodeOrNull(file) ?: return ""
        return try {
            recognizer.process(InputImage.fromBitmap(bitmap, rotationOf(file))).await().text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(TAG, "cannot recognise text in '${file.name}': ${e.message}")
            ""
        } finally {
            // Released here rather than left to the collector: a round can walk through dozens of
            // photographs, and each one would otherwise hold its pixels until the next GC.
            bitmap.recycle()
        }
    }

    private fun decodeOrNull(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = ImageDecodeRules.sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdgePx)
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }
    } catch (e: Throwable) {
        Log.d(TAG, "cannot decode '${file.name}': ${e.message}")
        null
    }

    /** Degrees clockwise, which is the unit `InputImage` takes. */
    private fun rotationOf(file: File): Int = try {
        val orientation = ExifInterface(file).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Throwable) {
        // Not a JPEG, or no EXIF to read: the image is upright as stored.
        0
    }
}
