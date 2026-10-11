package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The default accelerator.
 *
 * Worth its own test because the default is invisible until it is wrong: the switch shows the
 * resolved value, so nothing on screen distinguishes "the user chose the CPU" from "nobody chose and
 * the model is text-only". A regression here would show up as a rebuild nobody asked for, or as a
 * GPU that never gets used.
 */
class EmbeddingAcceleratorRulesTest {

    @Test
    fun `an unanswered switch follows the model`() {
        assertTrue(EmbeddingAcceleratorRules.useGpu(explicit = null, embedsImages = true))
        assertTrue(!EmbeddingAcceleratorRules.useGpu(explicit = null, embedsImages = false))
    }

    @Test
    fun `an answered switch is never second-guessed`() {
        // Both directions: a user who turned it ON for a text-only model keeps it, and one who
        // turned it OFF for a vision model keeps that too. The default is a default, not a policy.
        assertTrue(EmbeddingAcceleratorRules.useGpu(explicit = true, embedsImages = false))
        assertTrue(!EmbeddingAcceleratorRules.useGpu(explicit = false, embedsImages = true))
    }

    @Test
    fun `only a curated vision model counts as embedding images`() {
        // The curated multimodal entry - the one file whose vision tower is what makes the GPU
        // worth switching to.
        assertTrue(
            EmbeddingAcceleratorRules.selectedModelEmbedsImages(
                "embeddinggemma-2-text-vision-440m.litertlm"
            )
        )

        // Everything else lands on the CPU, which is the safe direction: a text-only model gains
        // nothing from the GPU, an uncurated `.litertlm` is refused at load time anyway, and the
        // empty string is "automatic" - the file that will run is not known from the setting alone.
        listOf(
            "bge-small-zh-v1.5-q8_0.gguf",
            "embeddinggemma-2-Q8_0.gguf",
            "someone-elses.litertlm",
            "",
            null,
        ).forEach { file ->
            assertTrue(
                "$file should not be treated as embedding images",
                !EmbeddingAcceleratorRules.selectedModelEmbedsImages(file),
            )
        }
    }
}
