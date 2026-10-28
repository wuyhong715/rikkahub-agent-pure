package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.data.vector.ColdMemorySearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecallTransformerTest {

    private fun hit(file: String, text: String, score: Float, chunk: Int = 0) =
        ColdMemorySearchHit(file = file, chunkIndex = chunk, score = score, text = text)

    @Test
    fun `one line per file, and the best chunk wins`() {
        val rendered = renderRecall(
            listOf(
                hit("A.md", "weak mention", 0.2f, chunk = 0),
                hit("A.md", "the strong passage", 0.9f, chunk = 1),
                hit("B.md", "another document", 0.5f),
            )
        )

        assertTrue(rendered.contains("<recall>"))
        assertTrue(rendered.contains("the strong passage"))
        assertFalse(rendered.contains("weak mention"))
        // Highest score first, and each file appears once.
        assertTrue(rendered.indexOf("A.md") < rendered.indexOf("B.md"))
        assertEquals(1, rendered.split("A.md").size - 1)
    }

    @Test
    fun `at most three documents are listed`() {
        val rendered = renderRecall(
            listOf(
                hit("A.md", "a", 0.9f),
                hit("B.md", "b", 0.8f),
                hit("C.md", "c", 0.7f),
                hit("D.md", "d", 0.6f),
            )
        )

        assertTrue(rendered.contains("A.md"))
        assertTrue(rendered.contains("C.md"))
        assertFalse(rendered.contains("D.md"))
        assertEquals(3, rendered.lines().count { it.startsWith("- ") })
    }

    @Test
    fun `long passages are flattened and cut`() {
        val rendered = renderRecall(
            listOf(hit("A.md", "line one\n\nline two " + "x".repeat(200), 0.9f)),
            maxChars = 20,
        )

        assertFalse(rendered.contains("\n\nline two"))
        assertTrue(rendered.contains("…"))
    }

    @Test
    fun `nothing found injects nothing`() {
        assertEquals("", renderRecall(emptyList()))
        assertEquals("", renderRecall(listOf(hit("A.md", "a", 0.9f)), maxItems = 0))
    }
}
