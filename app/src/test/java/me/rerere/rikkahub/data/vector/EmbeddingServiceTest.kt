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

    private companion object {
        const val ASYMMETRIC_FILE = "asymmetric-q8_0.gguf"
        const val DIM = 512
    }
}
