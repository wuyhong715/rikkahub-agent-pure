package me.rerere.rikkahub.data.vector

import me.rerere.locallm.litert.LitertEmbeddingCatalog
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one question the switch and the indexer both ask.
 *
 * Worth a test rather than a call site each, because the two answers must be the same one: a
 * switch that says "images are indexed as images" over a coordinator that quietly took the OCR
 * path is a screen lying about what happened to the user's photographs.
 */
class EmbeddingReadinessTest {

    private val visionFile = LitertEmbeddingCatalog.ENTRIES.single().file

    @Test
    fun `a vision litertlm reads images`() {
        assertTrue(EmbeddingReadiness.localModelReadsImages(Settings(), listOf(visionFile)))
    }

    @Test
    fun `a text-only gguf does not`() {
        assertFalse(
            EmbeddingReadiness.localModelReadsImages(
                Settings(),
                listOf("embeddinggemma-2-Q8_0.gguf"),
            )
        )
    }

    @Test
    fun `nothing installed reads nothing`() {
        assertFalse(EmbeddingReadiness.localModelReadsImages(Settings(), emptyList()))
    }

    /**
     * A cloud backend the app cannot actually call falls back to the local file - the same
     * fallback [EmbeddingReadiness.isReady] describes - so the answer still follows the file on
     * disk. Only a *usable* cloud model would make it false, and it could not be built here
     * without a provider list.
     */
    @Test
    fun `an unusable cloud choice still answers for the local file`() {
        assertTrue(
            EmbeddingReadiness.localModelReadsImages(
                Settings(embeddingBackend = CloudEmbeddingRules.BACKEND_CLOUD),
                listOf(visionFile),
            )
        )
    }
}
