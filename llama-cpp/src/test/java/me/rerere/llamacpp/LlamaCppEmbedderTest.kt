package me.rerere.llamacpp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The embedder's state machine, driven through a fake native seam: which handles exist after
 * each transition, what the RAM guard refuses, and - the part that really needs a test - that
 * no failure path leaks native memory. A leaked embedding model is a few hundred megabytes
 * resident for the life of the process, and nothing visible would point at the cause.
 */
class LlamaCppEmbedderTest {

    private class FakeEmbedNative : LlamaCppEmbedNative {
        var loadedPath: String? = null
        var freedModels = mutableListOf<Long>()
        var freedEngines = mutableListOf<Long>()
        var lastEmbedHandle: Long? = null
        var lastEmbedText: String? = null
        var createdEmbedCtx: Int? = null
        var createdThreads: Int? = null

        var modelInfoJson: String =
            """{"n_layers":24,"n_embd":512,"n_head_kv":2,"n_embd_head_k":256,"n_embd_head_v":256,
                "n_vocab":262144,"n_ctx_train":262144,"sliding_window":1024,
                "weights_bytes":309855456}""".trimIndent()

        var embedInfoJson: String =
            """{"dim":768,"pooling":"mean","has_encoder":false,"has_decoder":true,
                "n_ctx_train":262144}""".trimIndent()

        var embedCtxFails = false
        var embedInfoFails = false
        var loadModelFails = false
        var vector: FloatArray = floatArrayOf(3f, 4f)

        override fun loadModel(path: String): Long {
            if (loadModelFails) throw RuntimeException("failed to load model: $path")
            loadedPath = path
            return MODEL
        }

        override fun freeModel(handle: Long) {
            freedModels += handle
        }

        override fun modelInfo(handle: Long): String = modelInfoJson

        override fun createEmbedContext(modelHandle: Long, nCtx: Int, nThreads: Int): Long {
            if (embedCtxFails) throw RuntimeException("failed to create the embedding context")
            createdEmbedCtx = nCtx
            createdThreads = nThreads
            return ENGINE
        }

        override fun freeEmbedContext(handle: Long) {
            freedEngines += handle
        }

        override fun embedModelInfo(handle: Long): String {
            if (embedInfoFails) throw RuntimeException("embedding handle is null")
            return embedInfoJson
        }

        override fun embed(handle: Long, text: String): FloatArray {
            lastEmbedHandle = handle
            lastEmbedText = text
            return vector
        }

        companion object {
            const val MODEL = 11L
            const val ENGINE = 22L
        }
    }

    @Test
    fun `load reads the facts the engine reports`() = runBlocking {
        val native = FakeEmbedNative()
        val embedder = LlamaCppEmbedder(native)

        val info = embedder.load("/models/edg2.gguf")

        assertEquals(768, info.dim)
        assertEquals("mean", info.pooling)
        assertFalse(info.hasEncoder)
        assertTrue(info.hasDecoder)
        assertEquals(262_144, info.nCtxTrain)
        assertEquals("/models/edg2.gguf", native.loadedPath)
        assertEquals(LlamaCppEmbedder.DEFAULT_CONTEXT_TOKENS, native.createdEmbedCtx)
        assertTrue(embedder.isLoaded)
        assertEquals(info, embedder.info)
        assertTrue(native.freedModels.isEmpty())
    }

    @Test
    fun `load honours a caller-supplied context size`() = runBlocking {
        val native = FakeEmbedNative()
        LlamaCppEmbedder(native).load("/m.gguf", nCtx = 512)
        assertEquals(512, native.createdEmbedCtx)
    }

    @Test
    fun `embed forwards the handle and the exact text`() = runBlocking {
        val native = FakeEmbedNative()
        val embedder = LlamaCppEmbedder(native)
        embedder.load("/m.gguf")

        val raw = embedder.embed("猫 sitting on the mat 🐈")

        assertEquals(FakeEmbedNative.ENGINE, native.lastEmbedHandle)
        assertEquals("猫 sitting on the mat 🐈", native.lastEmbedText)
        assertEquals(2, raw.size)
    }

    @Test
    fun `embedNormalized returns a unit vector`() = runBlocking {
        val native = FakeEmbedNative().apply { vector = floatArrayOf(3f, 4f) }
        val embedder = LlamaCppEmbedder(native)
        embedder.load("/m.gguf")

        val normalized = embedder.embedNormalized("anything")

        assertEquals(0.6f, normalized[0], 1e-5f)
        assertEquals(0.8f, normalized[1], 1e-5f)
    }

    @Test
    fun `embed before load is refused`() {
        val embedder = LlamaCppEmbedder(FakeEmbedNative())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { embedder.embed("hello") }
        }
    }

    @Test
    fun `unload releases the context before the model`() = runBlocking {
        val native = FakeEmbedNative()
        val embedder = LlamaCppEmbedder(native)
        embedder.load("/m.gguf")

        embedder.unload()

        assertEquals(listOf(FakeEmbedNative.ENGINE), native.freedEngines)
        assertEquals(listOf(FakeEmbedNative.MODEL), native.freedModels)
        assertFalse(embedder.isLoaded)
        assertNull(embedder.info)
    }

    @Test
    fun `a second load releases the first model`() = runBlocking {
        val native = FakeEmbedNative()
        val embedder = LlamaCppEmbedder(native)
        embedder.load("/first.gguf")
        embedder.load("/second.gguf")

        assertEquals("/second.gguf", native.loadedPath)
        assertEquals(listOf(FakeEmbedNative.MODEL), native.freedModels)
        assertEquals(listOf(FakeEmbedNative.ENGINE), native.freedEngines)
    }

    @Test
    fun `an over-budget model is refused before any context is built`() = runBlocking {
        val native = FakeEmbedNative()
        val embedder = LlamaCppEmbedder(native)

        val thrown = assertThrows(ModelTooLargeException::class.java) {
            runBlocking { embedder.load("/big.gguf", availableRamBytes = 100_000_000L) }
        }

        assertTrue(thrown.message!!.contains("available"))
        assertNull(native.createdEmbedCtx)
        assertEquals(listOf(FakeEmbedNative.MODEL), native.freedModels)
        assertFalse(embedder.isLoaded)
    }

    @Test
    fun `unreadable weights metadata is refused rather than treated as small`() = runBlocking {
        val native = FakeEmbedNative().apply { modelInfoJson = """{"n_layers":24}""" }
        val embedder = LlamaCppEmbedder(native)

        assertThrows(ModelTooLargeException::class.java) {
            runBlocking { embedder.load("/m.gguf", availableRamBytes = 4_000_000_000L) }
        }
        assertNull(native.createdEmbedCtx)
        assertEquals(listOf(FakeEmbedNative.MODEL), native.freedModels)
    }

    @Test
    fun `a context that fails to build does not leak the model`() = runBlocking {
        val native = FakeEmbedNative().apply { embedCtxFails = true }
        val embedder = LlamaCppEmbedder(native)

        assertThrows(RuntimeException::class.java) {
            runBlocking { embedder.load("/m.gguf") }
        }
        assertEquals(listOf(FakeEmbedNative.MODEL), native.freedModels)
        assertTrue(native.freedEngines.isEmpty())
        assertFalse(embedder.isLoaded)
    }

    @Test
    fun `unreadable embed info does not leak the model or the context`() = runBlocking {
        // The dangerous pair: the context is native memory nothing else references, so a
        // throw between creating it and storing the handle has to free it here.
        val native = FakeEmbedNative().apply { embedInfoFails = true }
        val embedder = LlamaCppEmbedder(native)

        assertThrows(RuntimeException::class.java) {
            runBlocking { embedder.load("/m.gguf") }
        }
        assertEquals(listOf(FakeEmbedNative.ENGINE), native.freedEngines)
        assertEquals(listOf(FakeEmbedNative.MODEL), native.freedModels)
        assertFalse(embedder.isLoaded)
    }

    @Test
    fun `a failed load leaves nothing behind to embed with`() {
        // Block body, not `= runBlocking { ... }`: the expression form would hand JUnit the
        // value of the last statement (here a Throwable), and JUnit rejects a test method that
        // is not void.
        val native = FakeEmbedNative().apply { embedCtxFails = true }
        val embedder = LlamaCppEmbedder(native)

        runCatching { runBlocking { embedder.load("/m.gguf") } }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { embedder.embed("hello") }
        }
    }

    @Test
    fun `pooling is taken from the model, not assumed`() = runBlocking {
        // Qwen3-Embedding declares last-token pooling; a hardcoded mean would silently produce
        // a different (wrong) vector for every input.
        val native = FakeEmbedNative().apply {
            embedInfoJson =
                """{"dim":1024,"pooling":"last","has_encoder":false,"has_decoder":true,
                    "n_ctx_train":32768}""".trimIndent()
        }
        val info = LlamaCppEmbedder(native).load("/qwen.gguf")
        assertEquals("last", info.pooling)
        assertEquals(1024, info.dim)
    }
}
