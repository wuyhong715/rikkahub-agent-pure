package me.rerere.rikkahub.data.vector

import java.io.File
import kotlinx.coroutines.runBlocking
import me.rerere.llamacpp.LlamaCppEmbedNative
import me.rerere.llamacpp.LlamaCppEmbedder
import me.rerere.llamacpp.LlamaCppEmbeddingEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Which door a text goes through, and what the model does to it on the way.
 *
 * The prefixes themselves live in the catalogue and are tested there. What only this can test is
 * that [EmbeddingService] *applies* them - the documents on one side, the query on the other - and
 * that it applies nothing to a file that is not one of ours. Getting this backwards does not
 * throw, it just retrieves worse, which is why it is worth a test that records the exact strings
 * the native layer was handed.
 */
class EmbeddingServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Records every text the embedder is asked to embed, in order. */
    private class RecordingNative(private val dim: Int) : LlamaCppEmbedNative {
        val embedded = mutableListOf<String>()

        /** Every model path the embedder was asked to open, so a reload is observable. */
        val loaded = mutableListOf<String>()

        /** The thread each [embed] call ran on; the main-thread test at the bottom reads this. */
        val threads = mutableListOf<Thread>()

        override fun loadModel(path: String): Long {
            loaded += path
            return MODEL
        }
        override fun freeModel(handle: Long) = Unit
        override fun modelInfo(handle: Long): String = """{"weights_bytes":1000}"""
        override fun createEmbedContext(modelHandle: Long, nCtx: Int, nThreads: Int): Long = ENGINE
        override fun freeEmbedContext(handle: Long) = Unit
        override fun embedModelInfo(handle: Long): String =
            """{"dim":$dim,"pooling":"mean","has_encoder":false,"has_decoder":true,"n_ctx_train":8192}"""

        override fun embed(handle: Long, text: String): FloatArray {
            embedded += text
            threads += Thread.currentThread()
            return FloatArray(dim) { 1f }
        }

        companion object {
            const val MODEL = 7L
            const val ENGINE = 8L
        }
    }

    private val asymmetric = LlamaCppEmbeddingEntry(
        displayName = "asymmetric",
        repo = "example/asymmetric",
        file = ASYMMETRIC_FILE,
        sizeBytes = 1_000L,
        dim = DIM,
        pooling = "last",
        minMemGb = 1,
        contextTokens = 8192,
        documentPrefix = "passage: ",
        queryPrefix = "query: ",
    )

    private fun serviceFor(
        native: RecordingNative,
        file: String,
        entry: LlamaCppEmbeddingEntry?,
    ): EmbeddingService {
        val dir = temp.newFolder("llamacpp")
        File(dir, file).writeText("not really a gguf")
        return EmbeddingService(
            modelDirs = { listOf(dir) },
            configuredFileName = { file },
            embedder = LlamaCppEmbedder(native),
            curatedOrder = listOf(file),
            entryFor = { name -> entry?.takeIf { it.file == name } },
        )
    }

    @Test
    fun `documents take the document prefix and the query takes the query prefix`() = runBlocking {
        val native = RecordingNative(DIM)
        val service = serviceFor(native, ASYMMETRIC_FILE, asymmetric)

        service.embedDocuments(listOf("alpha", "beta"))
        service.embedQuery("gamma")

        assertEquals(listOf("passage: alpha", "passage: beta", "query: gamma"), native.embedded)
    }

    @Test
    fun `an uncurated file is embedded exactly as given`() = runBlocking {
        // Whatever GGUF the user dropped into the models directory: no prefix, because we know
        // nothing about how it was trained and a guess would move the vectors.
        val native = RecordingNative(DIM)
        val service = serviceFor(native, "hand-installed.gguf", entry = null)

        service.embedDocuments(listOf("alpha"))
        service.embedQuery("gamma")

        assertEquals(listOf("alpha", "gamma"), native.embedded)
    }

    @Test
    fun `a symmetric model gets no prefix on either side`() = runBlocking {
        val symmetric = asymmetric.copy(queryPrefix = "", documentPrefix = "")
        val native = RecordingNative(DIM)
        val service = serviceFor(native, ASYMMETRIC_FILE, symmetric)

        service.embedDocuments(listOf("alpha"))
        service.embedQuery("gamma")

        assertEquals(listOf("alpha", "gamma"), native.embedded)
    }

    @Test
    fun `the active model carries the prefixes, not just the vectors`() = runBlocking {
        val service = serviceFor(RecordingNative(DIM), ASYMMETRIC_FILE, asymmetric)

        val model = service.ensureLoaded()!!

        assertEquals("passage: ", model.documentPrefix)
        assertEquals("query: ", model.queryPrefix)
        assertEquals(ASYMMETRIC_FILE, model.fileName)
        assertEquals(ASYMMETRIC_FILE, model.modelId)
        assertEquals(DIM, model.dim)
        // The window comes from the model, not from the request: what a caller has to size its
        // inputs by is what the runtime actually created.
        assertEquals(8192, model.contextTokens)
    }

    /**
     * The cloud half: what was asked for, in the order it was asked, and what the service made of
     * the answers.
     */
    private class RecordingCloud(private val dim: Int = CLOUD_DIM) : CloudEmbeddingCaller {
        val batches = mutableListOf<List<String>>()

        override suspend fun embed(
            candidate: CloudEmbeddingCandidate,
            inputs: List<String>,
        ): List<FloatArray> {
            batches += inputs
            // Element 0 is the text's own number, so a vector can be traced back to the text it was
            // made from - which is the only way a reassembly bug is visible from the outside.
            return inputs.map { text ->
                val n = text.removePrefix("t").toFloatOrNull() ?: 0f
                FloatArray(dim) { if (it == 0) n else 0f }
            }
        }
    }

    private fun cloudCandidate() = CloudEmbeddingCandidate(
        providerId = "provider-1",
        providerName = "OpenAI",
        modelUuid = "model-1",
        modelId = "text-embedding-3-small",
        displayName = "Embedding Small",
        supported = true,
        contextLength = 8191,
    )

    private fun cloudService(caller: CloudEmbeddingCaller, candidate: CloudEmbeddingCandidate = cloudCandidate()) =
        EmbeddingService(
            modelDirs = { listOf(temp.newFolder("cloud-models")) },
            configuredFileName = { null },
            cloudChoice = { candidate },
            cloud = caller,
        )

    @Test
    fun `a cloud model runs even when a local file is installed and configured`() = runBlocking {
        val native = RecordingNative(DIM)
        val cloud = RecordingCloud()
        val dir = temp.newFolder("both")
        File(dir, ASYMMETRIC_FILE).writeText("not really a gguf")
        val service = EmbeddingService(
            modelDirs = { listOf(dir) },
            configuredFileName = { ASYMMETRIC_FILE },
            embedder = LlamaCppEmbedder(native),
            curatedOrder = listOf(ASYMMETRIC_FILE),
            entryFor = { name -> asymmetric.takeIf { it.file == name } },
            cloudChoice = { cloudCandidate() },
            cloud = cloud,
        )

        val model = service.ensureLoaded()!!
        service.embedDocuments(listOf("alpha", "beta"))

        assertEquals(true, model.isCloud)
        // The setting decides, not what happens to be on disk: otherwise a leftover download would
        // quietly take over an endpoint the user is paying for.
        assertEquals(listOf(listOf("alpha", "beta")), cloud.batches)
        assertEquals(emptyList<String>(), native.embedded)
    }

    @Test
    fun `a cloud model carries its own identity and window, and no prefix`() = runBlocking {
        // The prefixes exist for asymmetric local models; a provider's endpoint takes the text as
        // it is, and a model that had to be told which side of the pair it was on would be told
        // here rather than silently embedded wrong.
        val cloud = RecordingCloud()
        val service = cloudService(cloud)

        val model = service.ensureLoaded()!!
        service.embedQuery("t7")

        assertEquals(CloudEmbeddingRules.indexModelId(cloudCandidate()), model.modelId)
        assertEquals(8191, model.contextTokens)
        assertEquals("", model.queryPrefix)
        assertEquals(listOf(listOf("t7")), cloud.batches)
    }

    @Test
    fun `a long document is sent in batches and reassembled in order`() = runBlocking {
        val cloud = RecordingCloud()
        val service = cloudService(cloud)
        val texts = (1..CloudEmbeddingRules.EMBED_BATCH * 2 + 1).map { "t$it" }

        val vectors = service.embedDocuments(texts)

        assertEquals(3, cloud.batches.size)
        assertEquals(CloudEmbeddingRules.EMBED_BATCH, cloud.batches[0].size)
        assertEquals(1, cloud.batches[2].size)
        assertEquals(texts.indices.map { (it + 1).toFloat() }, vectors.map { it[0] })
    }

    /**
     * The model must never run on the thread that asked for the vector.
     *
     * This is the regression guard for a device ANR: every index coordinator is handed
     * `AppScope`, whose dispatcher is `Dispatchers.Main` (see `RikkaHubApp.AppScope`), and
     * `ChatService.initializeConversation` fires the memory and library syncs from there on every
     * conversation *open*. The native call used to happen inline, so opening a conversation ahead
     * of the five-minute debounce embedded the whole memory directory on the main thread and the
     * system killed `RouteActivity`: "Waited 5000ms for MotionEvent" (captured as `data_app_anr`
     * in dropbox, 2026-10-11 19:28). [EmbeddingService.onWorker] is now the only way in, and this
     * is what keeps it that way.
     */
    @Test
    fun `an embedding never runs on the calling thread`() = runBlocking {
        val native = RecordingNative(DIM)
        val service = serviceFor(native, ASYMMETRIC_FILE, asymmetric)
        val caller = Thread.currentThread()

        service.embedDocuments(listOf("alpha"))
        service.embedQuery("gamma")

        assertEquals(2, native.threads.size)
        assertTrue(
            "the model ran on the caller's thread: ${native.threads.map { it.name }}",
            native.threads.none { it === caller },
        )
    }

    /**
     * The accelerator is part of what a vector *is*.
     *
     * LiteRT's two accelerators do not compute the same function - the same sentence comes back
     * with cosine 0.9997 between them - so an index that mixed them would rank slightly worse for
     * everything embedded before the switch, and would report nothing. Every staleness check in
     * this package is a comparison of `modelId`, which is why the accelerator has to be inside it:
     * that is what makes flipping the GPU switch rebuild rather than mix.
     */
    @Test
    fun `a LiteRT model's identity carries the accelerator it ran on`() {
        val cpu = EmbeddingService.ActiveModel(
            fileName = "eg2.litertlm",
            dim = 768,
            contextTokens = 512,
            accelerator = "cpu",
        )
        val gpu = cpu.copy(accelerator = "gpu")

        assertEquals("eg2.litertlm#cpu", cpu.modelId)
        assertEquals("eg2.litertlm#gpu", gpu.modelId)

        // A GGUF has one implementation, so its identity is exactly what it always was - an index
        // built before this existed must not be invalidated by it.
        val gguf = EmbeddingService.ActiveModel(fileName = "a.gguf", dim = 768, contextTokens = 8192)
        assertEquals("a.gguf", gguf.modelId)
    }

    /**
     * What the engine is loaded *from* is re-answered on every [EmbeddingService.ensureLoaded].
     *
     * The failure this guards is silent in the worst way. `loadLocked` used to return the resident
     * model without re-reading anything, so a model picked in the picker, a GPU switch flipped or
     * the cloud backend turned on took effect at the next *launch* rather than the next round of
     * indexing - and in the meantime the index was rebuilt with the old engine while its rows were
     * stamped with the new identity. Changing the configured file between two calls is the cheapest
     * way to see whether the second call actually re-opens.
     */
    @Test
    fun `a model chosen after the engine is loaded replaces it`() = runBlocking {
        val native = RecordingNative(DIM)
        val dir = temp.newFolder("llamacpp")
        val first = "first-q8_0.gguf"
        val second = "second-q8_0.gguf"
        File(dir, first).writeText("not really a gguf")
        File(dir, second).writeText("not really a gguf")
        var configured: String? = first

        val service = EmbeddingService(
            modelDirs = { listOf(dir) },
            configuredFileName = { configured },
            embedder = LlamaCppEmbedder(native),
            curatedOrder = listOf(first, second),
        )

        service.embedDocuments(listOf("alpha"))
        configured = second
        service.embedDocuments(listOf("beta"))

        assertEquals(
            listOf(first, second),
            native.loaded.map { it.substringAfterLast('/') },
        )
    }

    private companion object {
        const val ASYMMETRIC_FILE = "asymmetric-q8_0.gguf"
        const val DIM = 512
        const val CLOUD_DIM = 8
    }
}
