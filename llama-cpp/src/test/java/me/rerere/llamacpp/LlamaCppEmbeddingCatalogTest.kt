package me.rerere.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integrity checks for the curated embedding catalog, mirroring [LlamaCppCatalogTest]'s. These
 * are the invariants whose failure mode is a failed multi-hundred-megabyte download or a
 * picker entry that lies about the model it installs.
 */
class LlamaCppEmbeddingCatalogTest {

    /**
     * The exact repository, file name and byte size of every curated entry.
     *
     * Read off the HuggingFace API on 2026-10-10, and locked here because nothing else in the
     * build would notice a wrong one: a mistyped repo id or file name is not a compile error and
     * not a failed unit test, it is a 404 on the user's first download - after they have already
     * waited for it. Update these numbers and the entry together, against the live API, or not at
     * all.
     */
    @Test
    fun `the curated files are the ones the live repositories serve`() {
        val verified = mapOf(
            // repo -> (file, bytes)
            "ggml-org/embeddinggemma-2-GGUF" to ("embeddinggemma-2-Q8_0.gguf" to 309_855_456L),
            "unsloth/embeddinggemma-2-GGUF" to ("embeddinggemma-2-UD-Q4_K_XL.gguf" to 175_673_856L),
            "Qwen/Qwen3-Embedding-0.6B-GGUF" to ("Qwen3-Embedding-0.6B-Q8_0.gguf" to 639_150_592L),
            "mradermacher/Qwen3-Embedding-0.6B-GGUF" to
                ("Qwen3-Embedding-0.6B.Q4_K_M.gguf" to 396_475_040L),
            "CompendiumLabs/bge-small-zh-v1.5-gguf" to
                ("bge-small-zh-v1.5-q8_0.gguf" to 26_472_640L),
            "CompendiumLabs/bge-base-zh-v1.5-gguf" to
                ("bge-base-zh-v1.5-q8_0.gguf" to 110_001_568L),
            "CompendiumLabs/bge-large-zh-v1.5-gguf" to
                ("bge-large-zh-v1.5-q4_k_m.gguf" to 202_115_392L),
        )

        LlamaCppEmbeddingCatalog.ENTRIES.forEach { entry ->
            val expected = verified[entry.repo]
            assertNotNull("unverified repository in the catalog: ${entry.repo}", expected)
            assertEquals(expected!!.first, entry.file)
            assertEquals(expected.second, entry.sizeBytes)
        }
        assertEquals(verified.keys, LlamaCppEmbeddingCatalog.ENTRIES.map { it.repo }.toSet())
    }

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
    fun `every entry declares the context window the picker shows`() {
        LlamaCppEmbeddingCatalog.ENTRIES.forEach { entry ->
            assertTrue(
                "${entry.displayName} has a non-positive context window",
                entry.contextTokens > 0,
            )
            assertTrue("${entry.displayName} has a blank context label", entry.contextLabel.isNotBlank())
        }
    }

    @Test
    fun `a round context window is labelled in thousands`() {
        assertEquals("32K", LlamaCppEmbeddingCatalog.entryFor("Qwen3-Embedding-0.6B-Q8_0.gguf")!!.contextLabel)
        assertEquals("8K", LlamaCppEmbeddingCatalog.entryFor("embeddinggemma-2-Q8_0.gguf")!!.contextLabel)
        // 512 is not a round thousand, and rounding it to "1K" would overstate it.
        assertEquals("512", LlamaCppEmbeddingCatalog.entryFor("bge-small-zh-v1.5-q8_0.gguf")!!.contextLabel)
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
    fun `every rung and lane has exactly one model`() {
        // The grid is what a phone is answered with, so a missing cell is a phone with no
        // suggestion at all - and a duplicate would make the answer depend on list order.
        val cells = LlamaCppEmbeddingCatalog.SLOTS.map { it.tier to it.focus }
        assertEquals(cells.size, cells.toSet().size)
        EmbeddingTier.entries.forEach { tier ->
            EmbeddingFocus.entries.forEach { focus ->
                assertNotNull(
                    "no model for $tier/$focus",
                    LlamaCppEmbeddingCatalog.forSlot(tier, focus),
                )
            }
        }
        assertEquals(
            EmbeddingTier.entries.size * EmbeddingFocus.entries.size,
            LlamaCppEmbeddingCatalog.SLOTS.size,
        )
    }

    @Test
    fun `every rung is heavier than the one below it, in both lanes`() {
        // The only promise the tiers make: choosing a higher one costs the user more storage.
        // Without this the labels would be free-floating adjectives.
        EmbeddingFocus.entries.forEach { focus ->
            val sizes = EmbeddingTier.entries.map {
                LlamaCppEmbeddingCatalog.forSlot(it, focus)!!.entry.sizeBytes
            }
            assertEquals(sizes.sorted(), sizes)
            assertEquals(sizes.size, sizes.toSet().size)
        }
    }

    @Test
    fun `the Chinese lane is the smaller of the two at every rung`() {
        // Load-bearing, not incidental: EmbeddingTierRules declines to fall back from one lane to
        // the other on the grounds that the swap could never rescue a device that failed its own
        // lane. If a rung ever inverts this, that reasoning has to be revisited.
        EmbeddingTier.entries.forEach { tier ->
            val chinese = LlamaCppEmbeddingCatalog.forSlot(tier, EmbeddingFocus.CHINESE)!!.entry
            val multi = LlamaCppEmbeddingCatalog.forSlot(tier, EmbeddingFocus.MULTILINGUAL)!!.entry
            assertTrue(
                "$tier: ${chinese.file} is not smaller than ${multi.file}",
                chinese.sizeBytes <= multi.sizeBytes,
            )
        }
    }

    @Test
    fun `the grid leads the order a picker offers`() {
        val order = LlamaCppEmbeddingCatalog.PICKER_ORDER
        val slots = LlamaCppEmbeddingCatalog.SLOTS
        assertEquals(slots.map { it.entry }, order.take(slots.size))
        // Nothing curated is dropped from the list, and nothing appears twice.
        assertEquals(LlamaCppEmbeddingCatalog.ENTRIES.toSet(), order.toSet())
        assertEquals(order.size, order.toSet().size)
        // The automatic pick walks the same order, so what is offered first is what is used first.
        assertEquals(order.map { it.file }, LlamaCppEmbeddingCatalog.curatedOrder)
    }

    @Test
    fun `the light rung is where an install with nothing installed lands`() {
        // The default tier is the product decision this whole change turns on: first run costs as
        // little as possible.
        assertEquals(
            EmbeddingTier.LIGHT,
            LlamaCppEmbeddingCatalog.SLOTS.first().tier,
        )
    }

    @Test
    fun `a curated file name resolves back to its entry`() {
        LlamaCppEmbeddingCatalog.ENTRIES.forEach { entry ->
            assertEquals(entry, LlamaCppEmbeddingCatalog.entryFor(entry.file))
        }
    }

    @Test
    fun `a file we did not curate has no entry`() {
        // The picker lists whatever GGUF is on disk, so this is the common case for a model the
        // user copied in by hand - and the one where inventing a prefix would be a guess.
        assertNull(LlamaCppEmbeddingCatalog.entryFor("someone-elses-model.gguf"))
        assertNull(LlamaCppEmbeddingCatalog.entryFor("/tmp/path/Qwen3-Embedding-0.6B-Q8_0.gguf"))
    }

    @Test
    fun `the asymmetric models declare their query instruction`() {
        // The whole reason LlamaCppEmbeddingEntry grew prefix fields: these were trained to see a
        // retrieval instruction on the query and a bare passage on the other side, and dropping it
        // costs ranking quality without failing anything.
        val qwen = LlamaCppEmbeddingCatalog.entryFor("Qwen3-Embedding-0.6B-Q8_0.gguf")!!
        assertTrue(qwen.queryPrefix.startsWith("Instruct:"))
        assertTrue(qwen.queryPrefix.endsWith("Query: "))
        assertEquals("", qwen.documentPrefix)

        val bgeZh = LlamaCppEmbeddingCatalog.entryFor("bge-small-zh-v1.5-q8_0.gguf")!!
        assertTrue(bgeZh.queryPrefix.isNotBlank())
        assertEquals("", bgeZh.documentPrefix)
    }

    @Test
    fun `the symmetric models declare no prefix`() {
        // EmbeddingGemma 2 embeds a passage and a query the same way; a prefix here would be a
        // change to the vector space, not a correction of one.
        listOf("embeddinggemma-2-Q8_0.gguf", "embeddinggemma-2-UD-Q4_K_XL.gguf").forEach { file ->
            val entry = LlamaCppEmbeddingCatalog.entryFor(file)!!
            assertEquals("", entry.queryPrefix)
            assertEquals("", entry.documentPrefix)
        }
    }
}
