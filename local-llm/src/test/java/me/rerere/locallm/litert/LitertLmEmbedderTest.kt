package me.rerere.locallm.litert

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine, exercised through the [LitertEmbedNative] seam: no SDK, no native library, so
 * this runs on the JVM.
 *
 * What is worth testing here is not "does it embed" -- that is the SDK's business and was measured
 * on device -- but the parts a caller depends on: that the reported width comes from a real call
 * rather than a constant, that a failed load leaves nothing behind, and that a model swap frees the
 * previous engine instead of leaving two resident.
 */
class LitertLmEmbedderTest {

    private class FakeSession(
        private val dim: Int,
        private val onClose: () -> Unit,
        private val failOnEmbed: Throwable? = null,
    ) : LitertEmbedSession {
        var closed = false
            private set
        var lastImageBytes: ByteArray? = null
        var lastAudioBytes: ByteArray? = null

        override fun embed(texts: List<String>): List<FloatArray> {
            failOnEmbed?.let { throw it }
            return texts.map { text -> FloatArray(dim) { (text.length % 7).toFloat() } }
        }

        override fun embedImage(bytes: ByteArray): FloatArray {
            failOnEmbed?.let { throw it }
            lastImageBytes = bytes
            return FloatArray(dim) { (bytes.size % 5).toFloat() }
        }

        override fun embedAudio(bytes: ByteArray): FloatArray {
            failOnEmbed?.let { throw it }
            lastAudioBytes = bytes
            return FloatArray(dim) { (bytes.size % 3).toFloat() }
        }

        override fun close() {
            closed = true
            onClose()
        }
    }

    private class FakeNative(
        private val dim: Int = 768,
        private val failOpen: Throwable? = null,
        private val failEmbed: Throwable? = null,
        /** The backend whose `open` throws, as the GPU delegate does on real hardware. */
        private val failOpenOn: LitertEmbedBackend? = null,
    ) : LitertEmbedNative {
        val opened = mutableListOf<String>()
        val sessions = mutableListOf<FakeSession>()

        /** Every backend an open was attempted on, successful or not. */
        val attempted = mutableListOf<LitertEmbedBackend>()
        var lastSignature: Int? = null
        var lastBackend: LitertEmbedBackend? = null

        override fun open(
            modelPath: String,
            backend: LitertEmbedBackend,
            nativeLibraryDir: String,
            cacheDir: String?,
            signature: Int,
        ): LitertEmbedSession {
            attempted += backend
            if (backend == failOpenOn) {
                throw IllegalStateException("Failed to modify graph with delegate")
            }
            failOpen?.let { throw it }
            opened += modelPath
            lastSignature = signature
            lastBackend = backend
            return FakeSession(dim, onClose = {}, failOnEmbed = failEmbed).also { sessions += it }
        }
    }

    @Test
    fun `load reports the width the engine actually returns`() = runBlocking {
        val native = FakeNative(dim = 512)
        val embedder = LitertLmEmbedder(native = native)

        val info = embedder.load("/models/a.litertlm", signature = 512)

        assertEquals(512, info.dim)
        assertEquals(512, info.signature)
        assertTrue(embedder.isLoaded)
        assertEquals("/models/a.litertlm", embedder.loadedFile)
    }

    @Test
    fun `load passes the signature and the chosen backend through`() = runBlocking {
        val native = FakeNative()
        val embedder = LitertLmEmbedder(
            native = native,
            nativeLibraryDir = { "/data/lib" },
            cacheDir = { "/data/cache" },
        )

        embedder.load("/models/a.litertlm", signature = 2048, backend = LitertEmbedBackend.GPU)

        assertEquals(2048, native.lastSignature)
        assertEquals(LitertEmbedBackend.GPU, native.lastBackend)
    }

    @Test
    fun `load reports the accelerator the engine came up on`() = runBlocking {
        // The name goes into the identity of every vector this model produces, so it has to come
        // from the open call that ran rather than from anything the caller remembers.
        val embedder = LitertLmEmbedder(native = FakeNative())
        assertEquals("cpu", embedder.load("/models/a.litertlm", signature = 512).accelerator)

        val gpu = LitertLmEmbedder(native = FakeNative())
        assertEquals(
            "gpu",
            gpu.load("/models/a.litertlm", signature = 512, backend = LitertEmbedBackend.GPU)
                .accelerator,
        )
    }

    @Test
    fun `an accelerator that cannot open the file is retried on the CPU`() = runBlocking {
        // Not hypothetical: on a Snapdragon 8 Elite the GPU delegate cannot build this model's
        // graph - `Failed to modify graph with delegate` - and `initialize()` builds every graph at
        // once, so the whole engine fails. Without the retry a *speed* preference would take
        // semantic search with it: cold memory, the file library, history and the tool catalogue
        // are all behind this engine.
        val native = FakeNative(failOpenOn = LitertEmbedBackend.GPU)
        val embedder = LitertLmEmbedder(native = native)

        val info = embedder.load(
            "/models/a.litertlm",
            signature = 512,
            backend = LitertEmbedBackend.GPU,
        )

        assertEquals("cpu", info.accelerator)
        assertTrue(embedder.isLoaded)
        assertEquals(
            listOf(LitertEmbedBackend.GPU, LitertEmbedBackend.CPU),
            native.attempted,
        )
    }

    @Test
    fun `an accelerator that failed once is not attempted again`() = runBlocking {
        // The failure costs seconds and would otherwise be paid on every round of indexing, forever.
        val native = FakeNative(failOpenOn = LitertEmbedBackend.GPU)
        val embedder = LitertLmEmbedder(native = native)

        embedder.load("/models/a.litertlm", signature = 512, backend = LitertEmbedBackend.GPU)
        embedder.unload()
        embedder.load("/models/a.litertlm", signature = 512, backend = LitertEmbedBackend.GPU)

        assertEquals(1, native.attempted.count { it == LitertEmbedBackend.GPU })
    }

    @Test
    fun `a CPU open that fails is still a failure`() = runBlocking {
        // The downgrade is for a *backend* that will not work, not a blanket retry: with nothing
        // left to fall back to, the error has to reach the caller.
        val native = FakeNative(failOpen = IllegalStateException("no engine"))
        val embedder = LitertLmEmbedder(native = native)

        var thrown: Throwable? = null
        try {
            embedder.load("/models/a.litertlm", signature = 512, backend = LitertEmbedBackend.GPU)
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull(thrown)
        assertTrue(!embedder.isLoaded)
        assertEquals(
            listOf(LitertEmbedBackend.GPU, LitertEmbedBackend.CPU),
            native.attempted,
        )
    }

    @Test
    fun `switching the accelerator on the same file reopens the engine`() = runBlocking {
        // The two accelerators do not produce the same vectors, so a load that kept the CPU engine
        // while the caller asked for the GPU would hand back vectors under the wrong identity.
        val native = FakeNative()
        val embedder = LitertLmEmbedder(native = native)

        embedder.load("/models/a.litertlm", signature = 512)
        embedder.load("/models/a.litertlm", signature = 512, backend = LitertEmbedBackend.GPU)

        assertEquals(listOf("/models/a.litertlm", "/models/a.litertlm"), native.opened)
        assertEquals(LitertEmbedBackend.GPU, native.lastBackend)
    }

    @Test
    fun `loading the same file again is a no-op`() = runBlocking {
        val native = FakeNative()
        val embedder = LitertLmEmbedder(native = native)

        embedder.load("/models/a.litertlm", signature = 512)
        embedder.load("/models/a.litertlm", signature = 512)

        assertEquals(1, native.opened.size)
    }

    @Test
    fun `loading a different file frees the previous engine`() = runBlocking {
        val native = FakeNative()
        val embedder = LitertLmEmbedder(native = native)

        embedder.load("/models/a.litertlm", signature = 512)
        embedder.load("/models/b.litertlm", signature = 512)

        assertEquals(2, native.opened.size)
        assertTrue(native.sessions[0].closed)
        assertFalse(native.sessions[1].closed)
        assertEquals("/models/b.litertlm", embedder.loadedFile)
    }

    /**
     * A load that fails must not leave anything published: the service treats `isLoaded == false`
     * as "semantic search is off", and a half-open engine in the field would turn every later call
     * into a crash instead of a retry.
     */
    @Test
    fun `a failing load publishes nothing and can be retried`() = runBlocking {
        val native = FakeNative(failOpen = IllegalStateException("no such file"))
        val embedder = LitertLmEmbedder(native = native)

        runCatching { embedder.load("/models/a.litertlm", signature = 512) }

        assertFalse(embedder.isLoaded)
        assertNull(embedder.loadedFile)

        val working = FakeNative()
        val retried = LitertLmEmbedder(native = working)
        assertTrue(retried.load("/models/a.litertlm", signature = 512).dim > 0)
    }

    /** An engine that opens but cannot embed is closed, not held open for the next call to trip on. */
    @Test
    fun `an engine that fails its first embed is closed`() = runBlocking {
        val native = FakeNative(failEmbed = IllegalStateException("Failed to allocate tensors"))
        val embedder = LitertLmEmbedder(native = native)

        runCatching { embedder.load("/models/a.litertlm", signature = 512) }

        assertEquals(1, native.sessions.size)
        assertTrue(native.sessions.single().closed)
        assertFalse(embedder.isLoaded)
    }

    @Test
    fun `embedNormalized throws when nothing is loaded`() = runBlocking {
        val embedder = LitertLmEmbedder(native = FakeNative())
        val thrown = runCatching { embedder.embedNormalized("hello") }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `embedNormalized returns one vector per call`() = runBlocking {
        val embedder = LitertLmEmbedder(native = FakeNative(dim = 768))
        embedder.load("/models/a.litertlm", signature = 512)

        assertEquals(768, embedder.embedNormalized("hello").size)
    }

    @Test
    fun `embedImage returns one vector and hands the bytes through untouched`() = runBlocking {
        val native = FakeNative(dim = 768)
        val embedder = LitertLmEmbedder(native = native)
        embedder.load("/models/a.litertlm", signature = 512)

        val bytes = byteArrayOf(1, 2, 3, 4)
        val vector = embedder.embedImage(bytes)

        assertEquals(768, vector.size)
        assertArrayEquals(bytes, native.sessions.single().lastImageBytes)
    }

    @Test
    fun `embedImage throws when nothing is loaded`() = runBlocking {
        val embedder = LitertLmEmbedder(native = FakeNative())
        val thrown = runCatching { embedder.embedImage(byteArrayOf(1)) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `embedAudio hands the clip through untouched`() = runBlocking {
        val native = FakeNative(dim = 768)
        val embedder = LitertLmEmbedder(native = native)
        embedder.load("/models/a.litertlm", signature = 2048)

        val wav = byteArrayOf(82, 73, 70, 70, 0, 0, 0, 0)
        val vector = embedder.embedAudio(wav)

        assertEquals(768, vector.size)
        assertArrayEquals(wav, native.sessions.single().lastAudioBytes)
    }

    @Test
    fun `embedAudio throws when nothing is loaded`() = runBlocking {
        val embedder = LitertLmEmbedder(native = FakeNative())
        val thrown = runCatching { embedder.embedAudio(byteArrayOf(1)) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `unload frees the engine and clears the state`() = runBlocking {
        val native = FakeNative()
        val embedder = LitertLmEmbedder(native = native)
        embedder.load("/models/a.litertlm", signature = 512)

        embedder.unload()

        assertFalse(embedder.isLoaded)
        assertNull(embedder.loadedFile)
        assertTrue(native.sessions.single().closed)
    }
}
