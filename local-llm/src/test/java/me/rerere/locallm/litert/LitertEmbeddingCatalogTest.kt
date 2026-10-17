package me.rerere.locallm.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog is hand-written data, so the tests are checks on the data.
 *
 * Two of them are regression guards for things that actually went wrong on device, and both would
 * otherwise only be discovered as "the model downloads, the engine will not start".
 */
class LitertEmbeddingCatalogTest {

    /** The text+vision file: the one a fresh install gets. */
    private val entry = LitertEmbeddingCatalog.ENTRIES.first { it.vision && !it.audio }

    /** The text+vision+audio file: the one an audio index needs. */
    private val audioEntry = LitertEmbeddingCatalog.ENTRIES.first { it.audio }

    /**
     * The `litert-community` repos publish a per-SoC AOT build next to the plain file, and the
     * per-SoC ones do **not** work here: measured on a Snapdragon 8 Elite, the `_Qualcomm_SM8750`
     * file fails on CPU and NPU with `Failed to allocate tensors` and on GPU with `Failed to modify
     * graph with delegate`, while the plain file embeds on all three. Picking one by hand is a way
     * to break semantic search, so the curated entry must never name one.
     */
    @Test
    fun `the curated file is the plain one, not a per-SoC build`() {
        assertFalse(entry.file, entry.file.contains("_Qualcomm"))
        assertFalse(entry.file, entry.file.contains("_MediaTek"))
        assertFalse(entry.file, entry.file.contains("_Google_Tensor"))
        assertFalse(entry.file, entry.file.contains("_Intel"))
        assertEquals("embeddinggemma-2-text-vision-440m.litertlm", entry.file)
    }

    /** The signature must be one the file ships, or the load fails outright. */
    @Test
    fun `the signature is one the model publishes`() {
        assertTrue(
            "signature ${entry.signature} is not in the model's published set",
            entry.signature in listOf(128, 256, 512, 1024, 2048, 8192),
        )
    }

    /**
     * The window the chunker sizes against is the signature, not the headline context: the engine
     * refuses a text longer than the signature it was loaded with.
     */
    @Test
    fun `the effective window is the loaded signature`() {
        assertEquals(entry.signature, entry.contextTokens)
        assertTrue(entry.maxContextTokens >= entry.signature)
    }

    /** Download size as published by HuggingFace; a mismatch means the file was replaced upstream. */
    @Test
    fun `the size is the published one`() {
        assertEquals(387_710_976L, entry.sizeBytes)
    }

    /** The two sides of the asymmetric pair, verbatim from the model card. */
    @Test
    fun `the task prefixes are the documented ones`() {
        assertEquals("task: search result | query: ", entry.queryPrefix)
        assertEquals("title: none | text: ", entry.documentPrefix)
    }

    /**
     * The 440M is the text+vision variant, and that flag is what decides whether an image is
     * embedded as an image or handed to the OCR/description fallback. Getting it wrong is silent:
     * the fallback still produces a vector, just one made from whatever the reader noticed.
     */
    @Test
    fun `the curated model is the one that can see`() {
        assertTrue(entry.vision)
        assertTrue(entry.file.contains("text-vision"))
    }

    @Test
    fun `resolveUrl is the huggingface resolve path`() {
        assertEquals(
            "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm" +
                "/resolve/main/embeddinggemma-2-text-vision-440m.litertlm",
            entry.resolveUrl(),
        )
    }

    /** Lookup is by base name, so a path a caller happened to build still resolves. */
    @Test
    fun `entryFor matches a bare name and a path`() {
        assertNotNull(LitertEmbeddingCatalog.entryFor(entry.file))
        assertNotNull(LitertEmbeddingCatalog.entryFor("/data/user/0/app/files/local-models/litert/${entry.file}"))
        assertNull(LitertEmbeddingCatalog.entryFor("something-else.gguf"))
    }

    @Test
    fun `curatedOrder lists the curated files, the default first`() {
        assertEquals(LitertEmbeddingCatalog.ENTRIES.map { it.file }, LitertEmbeddingCatalog.curatedOrder)
        // The order is what an untouched install falls back to, and 485 MB of audio encoder is not
        // what somebody asking for "an embedding model" should get by default.
        assertEquals(entry.file, LitertEmbeddingCatalog.curatedOrder.first())
    }

    @Test
    fun `the audio file is the same plain file, with an encoder the other one lacks`() {
        assertFalse(audioEntry.file, audioEntry.file.contains("_Qualcomm"))
        assertEquals("embeddinggemma-2-740m.litertlm", audioEntry.file)
        assertEquals(484_622_336L, audioEntry.sizeBytes)
        assertTrue(audioEntry.audio)
        // The 740M is text + vision + audio; an audio index that could not read a video's frames
        // would be a worse file than no file at all.
        assertTrue(audioEntry.vision)
        assertFalse(entry.audio)
    }

    /**
     * The audio signature has to hold a segment.
     *
     * The model charges 25 tokens per second of sound, so the signature is what bounds a segment:
     * at 2048 the ceiling is ~80 s, which is what `AudioDecodeRules` plans for. A smaller signature
     * would make every segment over-long and the engine would refuse it outright.
     */
    @Test
    fun `the audio signature holds a whole segment`() {
        assertEquals(2_048, audioEntry.signature)
        // 75 s, the segment length the app's audio rules plan for (this module cannot see them).
        val tokensForOneSegment = 75 * 25
        assertTrue(
            "a segment needs $tokensForOneSegment tokens",
            tokensForOneSegment <= audioEntry.signature,
        )
    }
}
