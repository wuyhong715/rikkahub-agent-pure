package me.rerere.rikkahub.runtime.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truncation is a token decision, not a schema decision. These tests pin the part that matters to
 * the UI: after a result is shortened, it must still be the same JSON object, with the same field
 * names, so `ToolUIRenderer`s that read `text` / `path` keep rendering instead of silently falling
 * back to the generic card.
 */
class ToolResultEnvelopeTest {

    private fun meta(spill: String? = "abc.txt") = ToolResultEnvelope.Meta(
        notice = "Full output saved as: abc.txt",
        originalTokens = 5_000,
        resultTokens = 900,
        totalChars = 20_000,
        spillFile = spill,
    )

    private fun body(n: Int) = "x".repeat(n)

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a truncated file read keeps its shape`() {
        val original = """{"path":"/workspace/a.md","text":"${body(9000)}"}"""
        val rebuilt = requireNotNull(ToolResultEnvelope.rebuild(original, "HEAD…TAIL", meta()))
        val json = parse(rebuilt)

        assertEquals("/workspace/a.md", json["path"]?.jsonPrimitive?.content)
        assertEquals("HEAD…TAIL", json["text"]?.jsonPrimitive?.content)
        assertEquals("true", json["truncated"]?.jsonPrimitive?.content)
        assertEquals("abc.txt", json["spillFile"]?.jsonPrimitive?.content)
        assertEquals("5000", json["originalTokens"]?.jsonPrimitive?.content)
        assertTrue(json["notice"]?.jsonPrimitive?.content!!.contains("abc.txt"))
        assertTrue("rebuilt result must be smaller than the original", rebuilt.length < original.length)
    }

    @Test
    fun `a second oversized field cannot smuggle the budget`() {
        val original = """{"text":"${body(9000)}","stdout":"${body(5000)}","code":0}"""
        val json = parse(requireNotNull(ToolResultEnvelope.rebuild(original, "BODY", meta())))

        assertEquals("BODY", json["text"]?.jsonPrimitive?.content)
        assertTrue(json["stdout"]?.jsonPrimitive?.content!!.contains("elided"))
        assertEquals("0", json["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `oversized nested structures are capped too`() {
        val original = """{"path":"/a","text":"${body(9000)}","meta":{"k":["${body(3000)}"]}}"""
        val json = parse(requireNotNull(ToolResultEnvelope.rebuild(original, "BODY", meta())))

        assertTrue(json.containsKey("meta"))
        assertTrue(json["meta"].toString().contains("elided"))
    }

    @Test
    fun `no spill file means no spill key`() {
        val original = """{"path":"/a","text":"${body(9000)}"}"""
        val json = parse(requireNotNull(ToolResultEnvelope.rebuild(original, "BODY", meta(spill = null))))

        assertFalse(json.containsKey("spillFile"))
        assertTrue(json.containsKey("notice"))
    }

    @Test
    fun `the payload goes back to its named field, not the longest one`() {
        // After one pass the body is short, so a path can be longer than it. A length-only
        // heuristic would overwrite the path with the body; the priority list must win.
        val original = """{"path":"/workspace/a-very-long-file-name.md","text":"BODY"}"""
        val json = parse(requireNotNull(ToolResultEnvelope.rebuild(original, "BODY2", meta())))

        assertEquals("/workspace/a-very-long-file-name.md", json["path"]?.jsonPrimitive?.content)
        assertEquals("BODY2", json["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `results that cannot carry a body fall back to the plain notice`() {
        // The caller then uses buildTruncationNotice, which is the only honest shape for these.
        assertNull(ToolResultEnvelope.rebuild("just a log line", "B", meta()))
        assertNull(ToolResultEnvelope.rebuild("[1,2,3]", "B", meta()))
        assertNull(ToolResultEnvelope.rebuild("""{"a":1,"b":true}""", "B", meta()))
        assertNull(ToolResultEnvelope.rebuild("""{"a":""", "B", meta()))
    }

    @Test
    fun `rebuild is stable across two passes`() {
        val original = """{"path":"/a.md","text":"${body(9000)}"}"""
        val once = requireNotNull(ToolResultEnvelope.rebuild(original, "BODY", meta()))
        val twice = ToolResultEnvelope.rebuild(once, "BODY2", meta())

        assertNotNull(twice)
        assertEquals("BODY2", parse(requireNotNull(twice))["text"]?.jsonPrimitive?.content)
    }
}
