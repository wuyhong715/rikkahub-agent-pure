package me.rerere.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integrity checks for the curated embedding catalog, mirroring [LlamaCppCatalogTest]'s. These
 * are the invariants whose failure mode is a failed multi-hundred-megabyte download or a
 * picker entry that lies about the model it installs.
 */
class LlamaCppEmbeddingCatalogTest {

    @Test
    fun `every entry points at a gguf file`() {
        LlamaCppEmbeddingCatalog.ENTRIES.forEach { entry ->
            assertTrue(
                "${entry.displayName}: file \"${entry.file}\" is not a .gguf file",
                entry.file.endsWith(".gguf"),
            )
        }
    }

    @Test
    fun `every entry has a non-zero size`() {
        LlamaCppEmbeddingCatalog.ENTRIES.forEach { entry ->
            assertTrue(
                "${entry.displayName} has a non-positive sizeBytes",
                entry.sizeBytes > 0L,
            )
        }
    }

    @Test
    fun `every entry declares a width and a pooling rule`() {
        LlamaCppEmbeddingCatalog.ENTRIES.forEach { entry ->
            assertTrue("${entry.displayName} has a non-positive dim", entry.dim > 0)
            assertTrue("${entry.displayName} has a blank pooling rule", entry.pooling.isNotBlank())
        }
    }

    @Test
    fun `display names are unique`() {
        val names = LlamaCppEmbeddingCatalog.ENTRIES.map { it.displayName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `download urls are https resolve urls ending in gguf`() {
        LlamaCppEmbeddingCatalog.ENTRIES.forEach { entry ->
            val url = entry.resolveUrl()
            assertTrue(url, url.startsWith("https://huggingface.co/"))
            assertTrue(url, "/resolve/main/" in url)
            assertTrue(url, url.endsWith(".gguf"))
        }
    }

    @Test
    fun `no entry points at a multimodal projector`() {
        // The mmproj is the vision/audio half of EmbeddingGemma 2 and is useless to a
        // text-only embedder: shipping it would download half a gigabyte for nothing.
        LlamaCppEmbeddingCatalog.ENTRIES.forEach { entry ->
            assertTrue(
                "${entry.displayName} points at ${entry.file}",
                !entry.file.contains("mmproj", ignoreCase = true),
            )
        }
    }

    @Test
    fun `the recommended entry is EmbeddingGemma 2 text weights`() {
        val recommended = LlamaCppEmbeddingCatalog.ENTRIES.first()
        assertTrue(
            "the first entry is what the picker pre-selects",
            "recommended" in recommended.tags,
        )
        assertEquals("ggml-org/embeddinggemma-2-GGUF", recommended.repo)
        assertEquals("embeddinggemma-2-Q8_0.gguf", recommended.file)
    }
}
