package me.rerere.rikkahub.runtime.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spill handle is derived from a model-supplied tool-call id, so these tests are mostly
 * about what the app refuses: a name that could address a file outside the tool-output
 * directory must never resolve, however plausible it looks.
 */
class ToolOutputRulesTest {

    @Test
    fun `ordinary ids are preserved`() {
        assertEquals("call_abc-123.txt", ToolOutputRules.fileNameFor("call_abc-123"))
        assertEquals("toolu_01AbC.txt", ToolOutputRules.fileNameFor("toolu_01AbC"))
    }

    @Test
    fun `separators cannot escape the directory`() {
        listOf("../../shared_prefs/x", "a/b", "a\\b", "/etc/passwd").forEach { hostile ->
            val name = ToolOutputRules.fileNameFor(hostile)
            assertFalse("separator survived: $name", name.contains('/'))
            assertFalse("separator survived: $name", name.contains('\\'))
            assertTrue("must stay inside the directory: $name", !name.startsWith("."))
        }
    }

    @Test
    fun `long ids are bounded`() {
        val name = ToolOutputRules.fileNameFor("x".repeat(500))
        assertTrue(name.length <= ToolOutputRules.MAX_NAME_CHARS)
        assertTrue(name.endsWith(ToolOutputRules.EXTENSION))
    }

    @Test
    fun `blank id still produces a usable name`() {
        assertEquals("output.txt", ToolOutputRules.fileNameFor(""))
        val punctuated = ToolOutputRules.fileNameFor("///")
        assertTrue(punctuated.endsWith(".txt"))
        assertFalse(punctuated.contains('/'))
    }

    @Test
    fun `resolves the forms a model actually quotes back`() {
        assertEquals("abc.txt", ToolOutputRules.resolveRequestedFile("abc.txt"))
        assertEquals("abc.txt", ToolOutputRules.resolveRequestedFile("abc"))
        assertEquals("abc.txt", ToolOutputRules.resolveRequestedFile("/tool_outputs/abc.txt"))
        assertEquals("abc.txt", ToolOutputRules.resolveRequestedFile("  abc.txt  "))
    }

    @Test
    fun `rejects anything that is not a plain spill name`() {
        listOf(
            "",
            "   ",
            "../foo",
            "a/b.txt",
            "..",
            ".hidden",
            "has space.txt",
        ).forEach { request ->
            assertNull("should have been rejected: '$request'", ToolOutputRules.resolveRequestedFile(request))
        }
    }

    @Test
    fun `slice clamps offsets into range`() {
        val text = "0123456789"

        val negative = ToolOutputRules.slice(text, -5, 500)
        assertEquals(0, negative.offset)
        assertTrue(negative.content.isNotEmpty())

        val beyond = ToolOutputRules.slice(text, 999, 500)
        assertEquals(text.length, beyond.offset)
        assertEquals("", beyond.content)
        assertTrue(beyond.exhausted)
    }

    @Test
    fun `slice honours the page size and reports the next offset`() {
        val text = "a".repeat(50_000)
        val first = ToolOutputRules.slice(text, 0, 1_000)
        assertEquals(1_000, first.content.length)
        assertEquals(1_000, first.nextOffset)
        assertFalse(first.exhausted)

        var page = first
        var pages = 1
        while (!page.exhausted && pages < 100) {
            page = ToolOutputRules.slice(text, page.nextOffset, ToolOutputRules.MAX_SLICE_CHARS)
            pages++
        }
        assertTrue("paging must terminate", page.exhausted)
        assertEquals(text.length, page.totalChars)
        assertTrue("unexpected page count: $pages", pages in 1..4)
    }

    @Test
    fun `slice refuses an absurd page size`() {
        val text = "a".repeat(ToolOutputRules.MAX_SLICE_CHARS * 2)
        val page = ToolOutputRules.slice(text, 0, Int.MAX_VALUE)
        assertEquals(ToolOutputRules.MAX_SLICE_CHARS, page.content.length)
    }

    @Test
    fun `truncation notice names the file and the tool`() {
        val notice = buildTruncationNotice(
            kind = ContentKind.LOG,
            originalTokens = 12_000,
            resultTokens = 300,
            totalChars = 48_000,
            fileName = "call_1.txt",
            hasShellAccess = false,
            body = "BODY",
        )
        assertTrue(notice.contains("LOG"))
        assertTrue(notice.contains("12000"))
        assertTrue(notice.contains("call_1.txt"))
        assertTrue(notice.contains("tool_output_read"))
        assertTrue(notice.endsWith("BODY"))
        assertFalse("no shell hint without a shell", notice.contains("cat /tool_outputs"))
    }
}

class ToolResultBudgetTest {

    @Test
    fun `budget scales with the window`() {
        assertEquals(ToolResultBudget.MIN_TOKENS, ToolResultBudget.forContextLength(8_000))
        assertEquals(1_600, ToolResultBudget.forContextLength(32_000))
        assertEquals(ToolResultBudget.MAX_TOKENS, ToolResultBudget.forContextLength(128_000))
    }

    @Test
    fun `unknown window gets the conservative middle`() {
        assertEquals(ToolResultBudget.UNKNOWN_CONTEXT_TOKENS, ToolResultBudget.forContextLength(null))
        assertEquals(ToolResultBudget.UNKNOWN_CONTEXT_TOKENS, ToolResultBudget.forContextLength(0))
        assertEquals(ToolResultBudget.UNKNOWN_CONTEXT_TOKENS, ToolResultBudget.forContextLength(-1))
    }

    @Test
    fun `explicit setting wins over the derived default`() {
        assertEquals(5_000, ToolResultBudget.resolve(5_000, 8_000))
        assertEquals(1_600, ToolResultBudget.resolve(null, 32_000))
    }

    @Test
    fun `explicit zero means legacy gate rather than unbounded`() {
        assertNull(ToolResultBudget.resolve(0, 32_000))
        assertNull(ToolResultBudget.resolve(-10, 32_000))
    }

    @Test
    fun `a stamped name stays readable even for a very long call id`() {
        // Providers can send long tool-call ids, and the writer appends "-<8 hex>". Before this
        // rule the base had already used the 60-character budget, so the result exceeded the
        // 64-char stem the reader validates: the notice named a file `tool_output_read` refused.
        val longId = "call_" + "x".repeat(120)
        val name = ToolOutputRules.stampedFileNameFor(longId, "deadbeef")
        assertTrue("stamped name must fit the reader's rule: $name", ToolOutputRules.resolveRequestedFile(name) != null)
        assertTrue(name.endsWith("-deadbeef.txt"))
    }

    @Test
    fun `a stamped name is distinct for distinct content`() {
        val a = ToolOutputRules.stampedFileNameFor("call_0", "11111111")
        val b = ToolOutputRules.stampedFileNameFor("call_0", "22222222")
        assertTrue(a != b)
    }
}
