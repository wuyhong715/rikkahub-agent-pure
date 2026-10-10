package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an image becomes in the index.
 *
 * Two of these cases are the ones that bite: the empty text, which is a file that never enters the
 * index - and so is read (and, with a vision model on, uploaded) again every round, forever - and
 * the failure text, which is a sentence *about* the image being indexed as though it were written
 * on it.
 */
class ImageSummaryRulesTest {

    @Test
    fun `the description comes first and the recognised text after it`() {
        val text = ImageSummaryRules.compose(
            ocrText = "GGML_ASSERT(i01 >= 0 && i01 < ne01) failed",
            summary = "Android Studio 里 Gradle 构建失败的报错界面截图",
            name = "shot.png",
        )

        assertEquals(
            "Android Studio 里 Gradle 构建失败的报错界面截图\n\n" +
                "GGML_ASSERT(i01 >= 0 && i01 < ne01) failed",
            text,
        )
    }

    @Test
    fun `either half alone is enough`() {
        assertEquals(
            "一句描述",
            ImageSummaryRules.compose(ocrText = null, summary = "一句描述", name = "a.png"),
        )
        assertEquals(
            "some text",
            ImageSummaryRules.compose(ocrText = "some text", summary = null, name = "a.png"),
        )
        // A model that answered with whitespace is a model that answered nothing.
        assertEquals(
            "some text",
            ImageSummaryRules.compose(ocrText = "some text", summary = "   \n  ", name = "a.png"),
        )
        assertEquals(
            "some text",
            ImageSummaryRules.compose(ocrText = "\n some text \n", summary = null, name = "a.png"),
        )
    }

    @Test
    fun `an image nothing could be said about is indexed as its own name`() {
        // The alternative - an empty text - is a document the index never records, and a document
        // the index has never seen is one every later round reads first.
        val text = ImageSummaryRules.compose(ocrText = "", summary = null, name = "IMG_0001.jpg")

        assertEquals("Image: IMG_0001.jpg", text)
        assertTrue(text.isNotBlank())
        assertEquals(
            WorkspaceLibraryRules.placeholderForImage("IMG_0001.jpg"),
            ImageSummaryRules.compose(ocrText = "  ", summary = "", name = "IMG_0001.jpg"),
        )
    }

    @Test
    fun `an image is never indexed as a failure message`() {
        // The whole reason the library asks the model through its own door: `performOcr` answers
        // failures with strings that explain themselves to a chat model, and those strings must
        // never reach the index.
        val text = ImageSummaryRules.compose(ocrText = null, summary = null, name = "shot.png")

        assertFalse(text.contains("ERROR", ignoreCase = true))
        assertFalse(text.contains("could not", ignoreCase = true))
        assertFalse(text.contains("failed", ignoreCase = true))
    }

    @Test
    fun `changing the question, the model or the file asks a new question`() {
        val base = ImageSummaryRules.cacheKeyOf("library/a.png", 1_000, "model-1", "prompt A")

        assertEquals(base, ImageSummaryRules.cacheKeyOf("library/a.png", 1_000, "model-1", "prompt A"))
        // A re-worded prompt is a different question, so a remembered answer must not be reused -
        // this is what makes "I improved the prompt, re-describe my library" actually happen.
        assertTrue(base != ImageSummaryRules.cacheKeyOf("library/a.png", 1_000, "model-1", "prompt B"))
        assertTrue(base != ImageSummaryRules.cacheKeyOf("library/a.png", 1_000, "model-2", "prompt A"))
        // Same path, different size: the file was replaced, so it is a different picture.
        assertTrue(base != ImageSummaryRules.cacheKeyOf("library/a.png", 1_001, "model-1", "prompt A"))
        assertTrue(base != ImageSummaryRules.cacheKeyOf("library/b.png", 1_000, "model-1", "prompt A"))
    }

    @Test
    fun `describing needs both the switch and a model that can see`() {
        assertTrue(ImageSummaryRules.enabled(switchedOn = true, modelCanSeeImages = true))
        // Off by default, and the reason it is not "a vision model is configured": many installs
        // have one already, for chat attachments.
        assertFalse(ImageSummaryRules.enabled(switchedOn = false, modelCanSeeImages = true))
        // A model without the modality is one the provider will refuse, so the call cannot succeed.
        assertFalse(ImageSummaryRules.enabled(switchedOn = true, modelCanSeeImages = false))
        assertFalse(ImageSummaryRules.enabled(switchedOn = false, modelCanSeeImages = false))
    }
}
