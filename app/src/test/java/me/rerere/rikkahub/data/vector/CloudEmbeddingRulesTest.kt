package me.rerere.rikkahub.data.vector

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which cloud model runs, what it is called in the index, and the order its vectors come back in.
 *
 * The three things here that a *wrong* implementation would not fail on, and would not be noticed
 * for weeks: an identity that two models can share, a window that silently truncates every chunk,
 * and a batch loop that files vectors under the wrong passage.
 */
class CloudEmbeddingRulesTest {

    private fun candidate(
        providerId: String = "provider-1",
        modelUuid: String = "model-1",
        modelId: String = "text-embedding-3-small",
        supported: Boolean = true,
        contextLength: Int? = null,
    ) = CloudEmbeddingCandidate(
        providerId = providerId,
        providerName = "OpenAI",
        modelUuid = modelUuid,
        modelId = modelId,
        displayName = "Small",
        supported = supported,
        contextLength = contextLength,
    )

    @Test
    fun `only the cloud value turns the cloud backend on`() {
        // Anything else - the default "local", an empty string from a settings file written before
        // this existed, a value from a future version - runs the local model.
        assertTrue(CloudEmbeddingRules.isCloud(CloudEmbeddingRules.BACKEND_CLOUD))
        assertFalse(CloudEmbeddingRules.isCloud(CloudEmbeddingRules.BACKEND_LOCAL))
        assertFalse(CloudEmbeddingRules.isCloud(""))
        assertFalse(CloudEmbeddingRules.isCloud("Cloud"))
        assertFalse(CloudEmbeddingRules.isCloud("something-else"))
    }

    @Test
    fun `a choice that is unset or gone resolves to nothing`() {
        val candidates = listOf(candidate(modelUuid = "model-1"))

        assertEquals("model-1", CloudEmbeddingRules.pick("model-1", candidates)?.modelUuid)
        assertNull(CloudEmbeddingRules.pick(null, candidates))
        assertNull(CloudEmbeddingRules.pick("", candidates))
        assertNull(CloudEmbeddingRules.pick("   ", candidates))
        // A model the user deleted: nothing, rather than whatever else is in the list.
        assertNull(CloudEmbeddingRules.pick("model-2", candidates))
    }

    @Test
    fun `an unsupported provider is still resolvable, so it can be explained`() {
        // Filtering this out here would leave the picker showing nothing selected and no reason.
        val candidates = listOf(candidate(supported = false))

        assertEquals("model-1", CloudEmbeddingRules.pick("model-1", candidates)?.modelUuid)
        assertEquals(false, CloudEmbeddingRules.pick("model-1", candidates)?.supported)
    }

    @Test
    fun `the identity carries the provider as well as the model`() {
        val first = CloudEmbeddingRules.indexModelId(candidate(providerId = "provider-1"))
        val second = CloudEmbeddingRules.indexModelId(candidate(providerId = "provider-2"))

        assertTrue(first.startsWith(CloudEmbeddingRules.INDEX_MODEL_ID_PREFIX))
        assertTrue(first != second)
    }

    @Test
    fun `the identity survives a model name that contains a slash`() {
        // `openai/text-embedding-3-small` on OpenRouter, `BAAI/bge-m3` on several others. The
        // identity reaches the database through EmbeddingModelRules.modelIdOf, which keeps only
        // what follows the last slash - so a name with one would be truncated, and two models
        // could share an id without anything noticing.
        val id = CloudEmbeddingRules.indexModelId(candidate(modelId = "openai/text-embedding-3-small"))

        assertFalse(id.contains('/'))
        assertEquals(id, EmbeddingModelRules.modelIdOf(id))
    }

    @Test
    fun `the window is the model's own, or the default when it does not say`() {
        assertEquals(8191, CloudEmbeddingRules.contextTokens(candidate(contextLength = 8191)))
        assertEquals(
            CloudEmbeddingRules.DEFAULT_CONTEXT_TOKENS,
            CloudEmbeddingRules.contextTokens(candidate(contextLength = null)),
        )
        // Metadata zeros and negatives are the same thing as missing.
        assertEquals(
            CloudEmbeddingRules.DEFAULT_CONTEXT_TOKENS,
            CloudEmbeddingRules.contextTokens(candidate(contextLength = 0)),
        )
    }

    @Test
    fun `inputs are sent in batches and reassembled in order`() = runBlocking {
        val served = mutableListOf<List<String>>()
        val texts = (1..CloudEmbeddingRules.EMBED_BATCH * 2 + 1).map { "t$it" }

        val vectors = CloudEmbeddingRules.embedInBatches(texts) { batch ->
            served += batch
            batch.map { text -> floatArrayOf(text.removePrefix("t").toFloat()) }
        }

        assertEquals(3, served.size)
        assertEquals(CloudEmbeddingRules.EMBED_BATCH, served[0].size)
        assertEquals(CloudEmbeddingRules.EMBED_BATCH, served[1].size)
        assertEquals(1, served[2].size)
        assertEquals(texts.size, vectors.size)
        // The whole point: vector n belongs to text n, across the batch boundaries too.
        assertEquals(texts.indices.map { (it + 1).toFloat() }, vectors.map { it[0] })
    }

    @Test
    fun `nothing to embed is not a request`() = runBlocking {
        var asked = 0

        val vectors = CloudEmbeddingRules.embedInBatches(emptyList()) { asked++; emptyList() }

        assertEquals(0, asked)
        assertEquals(emptyList<FloatArray>(), vectors)
    }

    @Test
    fun `a short answer is refused`() {
        // A provider that silently drops an input would otherwise shift every later vector onto the
        // wrong passage - the failure this whole file exists to prevent.
        val failure = runCatching {
            runBlocking {
                CloudEmbeddingRules.embedInBatches(listOf("a", "b", "c")) { batch ->
                    batch.dropLast(1).map { floatArrayOf(1f) }
                }
            }
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("asked for 3"))
    }

    @Test
    fun `vectors of differing widths are refused`() {
        val failure = runCatching {
            runBlocking {
                CloudEmbeddingRules.embedInBatches(listOf("a", "b")) { batch ->
                    batch.mapIndexed { index, _ -> FloatArray(index + 1) { 1f } }
                }
            }
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("differing widths"))
    }
}
