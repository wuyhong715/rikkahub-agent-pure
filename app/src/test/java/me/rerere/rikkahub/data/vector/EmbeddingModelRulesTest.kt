package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddingModelRulesTest {

    private val curated = listOf("embeddinggemma-2-Q8_0.gguf", "embeddinggemma-2-UD-Q4_K_XL.gguf")

    @Test
    fun `installing the recommended model is enough`() {
        // The whole point of the fallback: no second setting to find.
        assertEquals(
            "embeddinggemma-2-Q8_0.gguf",
            EmbeddingModelRules.pick(null, listOf("embeddinggemma-2-Q8_0.gguf"), curated),
        )
    }

    @Test
    fun `a curated model wins over chat models that happen to be installed`() {
        assertEquals(
            "embeddinggemma-2-Q8_0.gguf",
            EmbeddingModelRules.pick(
                configured = null,
                installed = listOf("Qwen3-4B-Q4_K_M.gguf", "embeddinggemma-2-Q8_0.gguf"),
                curatedOrder = curated,
            ),
        )
    }

    @Test
    fun `an explicit choice is honoured over the fallback`() {
        assertEquals(
            "embeddinggemma-2-UD-Q4_K_XL.gguf",
            EmbeddingModelRules.pick(
                configured = "embeddinggemma-2-UD-Q4_K_XL.gguf",
                installed = curated,
                curatedOrder = curated,
            ),
        )
    }

    @Test
    fun `a configured model that is gone means nothing, not something else`() {
        // Quietly substituting another model would query an index built with a *different*
        // model, which returns plausible nonsense - the worst possible failure for a search.
        assertNull(
            EmbeddingModelRules.pick(
                configured = "uninstalled.gguf",
                installed = listOf("embeddinggemma-2-Q8_0.gguf"),
                curatedOrder = curated,
            ),
        )
    }

    @Test
    fun `a blank configured value falls back to auto-detection`() {
        assertEquals(
            "embeddinggemma-2-Q8_0.gguf",
            EmbeddingModelRules.pick("   ", listOf("embeddinggemma-2-Q8_0.gguf"), curated),
        )
    }

    @Test
    fun `nothing installed means no model`() {
        assertNull(EmbeddingModelRules.pick(null, listOf("Qwen3-0.6B-Q8_0.gguf"), curated))
        assertNull(EmbeddingModelRules.pick(null, emptyList(), curated))
    }

    @Test
    fun `the model id is the file name, never a path`() {
        // A path carries the app's versioned data directory, so a model id built from one would
        // change on every update and invalidate the entire index.
        assertEquals(
            "embeddinggemma-2-Q8_0.gguf",
            EmbeddingModelRules.modelIdOf("/data/user/0/excp.rikkahub.debug/files/local-models/llamacpp/embeddinggemma-2-Q8_0.gguf"),
        )
    }
}
