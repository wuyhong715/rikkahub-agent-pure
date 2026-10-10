package me.rerere.rikkahub.data.vector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope `memory_search` answers with.
 *
 * Tested here rather than through the tool because this is the part that *steers the model*: the
 * note that says "nothing is indexed yet, use memory_index instead", the hint on the unavailable
 * path (which is an instruction to install the model, not a pointer at a fallback), the truncation
 * flag. All of it reads fine in a diff and misleads a model in practice, so it is asserted, not
 * eyeballed.
 */
class MemorySearchEnvelopeTest {

    private fun hit(text: String = "run rel7.sh", file: String = "M03-vps.md") =
        ColdMemorySearchHit(file = file, chunkIndex = 2, score = 0.87f, text = text)

    @Test
    fun `a hit carries its file, position, score and text`() {
        val json = Json.parseToJsonElement(
            MemorySearchEnvelope.hits(
                dirLabel = "/workspace/memory",
                query = "release",
                outcome = ColdMemorySearchOutcome(available = true, hits = listOf(hit()), indexedDocuments = 3),
            )
        ).jsonObject

        assertEquals("/workspace/memory", json["dir"]!!.jsonPrimitive.content)
        assertEquals(3, json["indexedDocuments"]!!.jsonPrimitive.int)
        val first = json["hits"]!!.jsonArray[0].jsonObject
        assertEquals("M03-vps.md", first["file"]!!.jsonPrimitive.content)
        assertEquals(2, first["chunkIndex"]!!.jsonPrimitive.int)
        assertEquals("run rel7.sh", first["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an over-long passage is truncated and says so`() {
        val long = "x".repeat(MAX_HIT_CHARS + 500)
        val json = Json.parseToJsonElement(
            MemorySearchEnvelope.hits(
                dirLabel = "/d",
                query = "q",
                outcome = ColdMemorySearchOutcome(available = true, hits = listOf(hit(text = long)), indexedDocuments = 1),
            )
        ).jsonObject

        val first = json["hits"]!!.jsonArray[0].jsonObject
        assertEquals(MAX_HIT_CHARS, first["text"]!!.jsonPrimitive.content.length)
        assertTrue(first["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a short passage is not marked truncated`() {
        val json = Json.parseToJsonElement(
            MemorySearchEnvelope.hits(
                dirLabel = "/d",
                query = "q",
                outcome = ColdMemorySearchOutcome(available = true, hits = listOf(hit()), indexedDocuments = 1),
            )
        ).jsonObject
        assertFalse(json["hits"]!!.jsonArray[0].jsonObject.containsKey("truncated"))
    }

    @Test
    fun `an empty index points at the exact-name tools instead of at itself`() {
        // The failure this prevents: a model told only "no results" gives up on the notes, when
        // memory_index would have listed the one document that answers the question.
        val json = Json.parseToJsonElement(
            MemorySearchEnvelope.hits(
                dirLabel = "/d",
                query = "q",
                outcome = ColdMemorySearchOutcome(available = true, hits = emptyList(), indexedDocuments = 0),
            )
        ).jsonObject
        val note = json["note"]!!.jsonPrimitive.content
        assertTrue(note, note.contains("memory_index"))
        assertTrue(note, note.contains("rebuild"))
    }

    @Test
    fun `an index with no match suggests different words`() {
        val json = Json.parseToJsonElement(
            MemorySearchEnvelope.hits(
                dirLabel = "/d",
                query = "q",
                outcome = ColdMemorySearchOutcome(available = true, hits = emptyList(), indexedDocuments = 12),
            )
        ).jsonObject
        val note = json["note"]!!.jsonPrimitive.content
        assertTrue(note, note.contains("No passage matched"))
    }

    @Test
    fun `a stale index is flagged as needing a rebuild`() {
        val json = Json.parseToJsonElement(
            MemorySearchEnvelope.hits(
                dirLabel = "/d",
                query = "q",
                outcome = ColdMemorySearchOutcome(
                    available = true,
                    hits = listOf(hit()),
                    indexedDocuments = 1,
                    stale = true,
                ),
            )
        ).jsonObject
        assertTrue(json["stale"]!!.jsonPrimitive.boolean)
        assertTrue(json["staleNote"]!!.jsonPrimitive.content.contains("rebuild"))
    }

    @Test
    fun `unavailable names the reason and the way out`() {
        // "The way out" is an instruction now, not an alternative. This used to point at
        // `memory_read`, which was right while a keyword path existed and is exactly the fallback
        // Moxw removed: with no embedding model the search does not run at all, so the only
        // sentence worth sending is how to install one.
        val json = Json.parseToJsonElement(
            MemorySearchEnvelope.unavailable("no embedding model is installed")
        ).jsonObject
        assertEquals("unavailable", json["error"]!!.jsonPrimitive.content)
        assertEquals("no embedding model is installed", json["detail"]!!.jsonPrimitive.content)
        val hint = json["hint"]!!.jsonPrimitive.content
        assertTrue(hint, hint.contains("embedding model"))
        assertTrue(hint, hint.contains("install"))
        assertFalse(hint, hint.contains("memory_read"))
    }

    @Test
    fun `unavailable always carries a detail, even with no reason given`() {
        val json = Json.parseToJsonElement(MemorySearchEnvelope.unavailable(null)).jsonObject
        assertTrue(json["detail"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `a missing query is an argument error, not an empty result`() {
        val json = Json.parseToJsonElement(MemorySearchEnvelope.missingQuery()).jsonObject
        assertEquals("missing_argument", json["error"]!!.jsonPrimitive.content)
    }
}
