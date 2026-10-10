package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format of an OpenAI-compatible embedding call.
 *
 * Worth its own test because both halves here fail quietly: a request the provider reshapes, or a
 * response whose vectors are read in arrival order when the provider meant them in `index` order,
 * gives an index whose every row is labelled with the wrong passage - and search still answers.
 */
class EmbeddingsRulesTest {

    @Test
    fun `the body is the model and the inputs, and nothing else`() {
        val body = EmbeddingsRules.requestBody("text-embedding-3-small", listOf("alpha", "beta"))

        assertEquals("text-embedding-3-small", body["model"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("alpha", "beta"),
            body["input"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(setOf("model", "input"), body.keys)
    }

    @Test
    fun `vectors come back in the order of the inputs`() {
        val body = """{"data":[{"index":0,"embedding":[1.0,0.0]},{"index":1,"embedding":[0.0,1.0]}]}"""

        val vectors = EmbeddingsRules.parseVectors(body)

        assertEquals(2, vectors.size)
        assertArrayEquals(floatArrayOf(1f, 0f), vectors[0], 0f)
        assertArrayEquals(floatArrayOf(0f, 1f), vectors[1], 0f)
    }

    @Test
    fun `a response that arrived out of order is sorted by index`() {
        // The schema allows it, and reading it in arrival order would mislabel every vector.
        val body = """{"data":[{"index":1,"embedding":[0.0,1.0]},{"index":0,"embedding":[1.0,0.0]}]}"""

        val vectors = EmbeddingsRules.parseVectors(body)

        assertArrayEquals(floatArrayOf(1f, 0f), vectors[0], 0f)
        assertArrayEquals(floatArrayOf(0f, 1f), vectors[1], 0f)
    }

    @Test
    fun `a response without indices keeps its arrival order`() {
        val body = """{"data":[{"embedding":[1.0]},{"embedding":[2.0]}]}"""

        val vectors = EmbeddingsRules.parseVectors(body)

        assertEquals(listOf(1f, 2f), vectors.map { it[0] })
    }

    @Test
    fun `a refusal is reported in the provider's own words`() {
        val body = """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""

        val failure = runCatching { EmbeddingsRules.parseVectors(body) }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("Incorrect API key provided"))
    }

    @Test
    fun `a response with no data field says so`() {
        val failure = runCatching { EmbeddingsRules.parseVectors("""{"object":"list"}""") }
            .exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("`data`"))
    }

    @Test
    fun `an empty data array is refused rather than indexed as nothing`() {
        val failure = runCatching { EmbeddingsRules.parseVectors("""{"data":[]}""") }
            .exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("no vectors"))
    }

    @Test
    fun `a non-numeric embedding is refused`() {
        val body = """{"data":[{"index":0,"embedding":[1.0,"nope"]}]}"""

        val failure = runCatching { EmbeddingsRules.parseVectors(body) }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("not numeric"))
    }

    @Test
    fun `a response whose widths disagree is refused`() {
        // One model cannot answer two widths; taking either one would build an index that is half
        // one model's space and half another's.
        val body = """{"data":[{"index":0,"embedding":[1.0,2.0]},{"index":1,"embedding":[1.0]}]}"""

        val failure = runCatching { EmbeddingsRules.parseVectors(body) }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("mixed widths"))
    }

    @Test
    fun `a body that is not JSON at all is refused`() {
        val failure = runCatching { EmbeddingsRules.parseVectors("<html>502 Bad Gateway</html>") }
            .exceptionOrNull()

        assertTrue(failure != null)
    }
}
