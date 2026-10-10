package me.rerere.rikkahub.data.vector

import java.io.File
import kotlinx.coroutines.runBlocking
import me.rerere.llamacpp.LlamaCppEmbedNative
import me.rerere.llamacpp.LlamaCppEmbedder
import me.rerere.llamacpp.LlamaCppEmbeddingEntry
import org.junit.Assert.assertEquals
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

        override fun loadModel(path: String): Long = MODEL
        override fun freeModel(handle: Long) = Unit
        override fun modelInfo(handle: Long): String = """{"weights_bytes":1000}"""
        override fun createEmbedContext(modelHandle: Long, nCtx: Int, nThreads: Int): Long = ENGINE
        override fun freeEmbedContext(handle: Long) = Unit
        override fun embedModelInfo(handle: Long): String =
            """{"dim":$dim,"pooling":"mean","has_encoder":false,"has_decoder":true,"n_ctx_train":8192}"""

        override fun embed(handle: Long, text: String): FloatArray {
            embedded += text
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
            modelsDir = { dir },
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
            modelsDir = { temp.newFolder("cloud-models") },
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
            modelsDir = { dir },
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

    private companion object {
        const val ASYMMETRIC_FILE = "asymmetric-q8_0.gguf"
        const val DIM = 512
        const val CLOUD_DIM = 8
    }
}
