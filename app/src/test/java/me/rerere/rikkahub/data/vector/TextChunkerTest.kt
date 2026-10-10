package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    private val noBreadcrumb = ChunkSpec(headingBreadcrumb = false)

    @Test
    fun `blank text produces no chunks`() {
        assertEquals(emptyList<TextChunk>(), TextChunker.chunk(""))
        assertEquals(emptyList<TextChunk>(), TextChunker.chunk("   \n\n \t "))
    }

    @Test
    fun `a short document is one chunk`() {
        val chunks = TextChunker.chunk("just one line", noBreadcrumb)
        assertEquals(1, chunks.size)
        assertEquals("just one line", chunks[0].text)
        assertEquals(0, chunks[0].index)
    }

    @Test
    fun `indices are gapless so a chunk has a stable identity`() {
        val text = (1..20).joinToString("\n\n") { "paragraph $it ".repeat(20) }
        val chunks = TextChunker.chunk(text, noBreadcrumb)
        assertTrue(chunks.size > 2)
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
    }

    @Test
    fun `no chunk body exceeds the ceiling`() {
        // The ceiling is what keeps a chunk inside the embedding context; a chunk that is
        // allowed to overshoot by "just the overlap" can silently overflow it.
        val spec = ChunkSpec(maxChars = 300, overlapChars = 80, headingBreadcrumb = false)
        val text = (1..30).joinToString("\n\n") { "sentence $it ".repeat(12) }
        val chunks = TextChunker.chunk(text, spec)
        assertTrue(chunks.size > 3)
        chunks.forEach { chunk ->
            assertTrue(
                "chunk ${chunk.index} is ${chunk.text.length} chars",
                chunk.text.length <= spec.maxChars,
            )
        }
    }

    @Test
    fun `all content survives chunking`() {
        val spec = ChunkSpec(maxChars = 200, overlapChars = 40, headingBreadcrumb = false)
        val paragraphs = (1..12).map { "para$it " + "x".repeat(50) }
        val chunks = TextChunker.chunk(paragraphs.joinToString("\n\n"), spec)

        // Every paragraph must appear somewhere: chunking may overlap and may split a paragraph,
        // but it must never drop one.
        val joined = chunks.joinToString("\n") { it.text }
        paragraphs.forEach { paragraph ->
            val head = paragraph.take(20)
            assertTrue("lost $head", joined.contains(head))
        }
    }

    @Test
    fun `consecutive chunks overlap so a straddling sentence is whole somewhere`() {
        // The ceiling has to leave room for the overlap *plus* the next piece, otherwise the
        // overlap is dropped rather than allowed to push the chunk past maxChars.
        val spec = ChunkSpec(maxChars = 300, overlapChars = 60, headingBreadcrumb = false)
        val first = "a".repeat(190)
        val second = "b".repeat(190)
        val chunks = TextChunker.chunk("$first\n\n$second", spec)
        assertEquals(2, chunks.size)
        assertTrue(chunks[1].text.startsWith("a".repeat(60)))
        assertTrue(chunks[1].text.contains(second))
    }

    @Test
    fun `a single enormous line is split without losing a character`() {
        // Minified JSON, a base64 blob, a one-line minified bundle: there is no boundary to
        // respect, so the splitter must fall back to cutting at exactly the budget.
        val spec = ChunkSpec(maxChars = 100, overlapChars = 0, headingBreadcrumb = false)
        val text = "z".repeat(950)
        val chunks = TextChunker.chunk(text, spec)
        assertEquals(10, chunks.size)
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test
    fun `a short trailing paragraph is kept rather than dropped`() {
        // Dropping it would lose content; merging it would exceed the ceiling. Keeping it is the
        // only option that neither loses retrieval nor breaks the size contract.
        val spec = ChunkSpec(maxChars = 100, overlapChars = 0, headingBreadcrumb = false)
        val chunks = TextChunker.chunk("a".repeat(98) + "\n\nend", spec)
        assertEquals(2, chunks.size)
        assertEquals("end", chunks[1].text)
    }

    // --- markdown ----------------------------------------------------------

    @Test
    fun `markdown chunks carry the heading path they sit under`() {
        val text = buildString {
            appendLine("# Deploy")
            appendLine()
            appendLine("run rel7.sh on the box ".repeat(8))
        }
        val chunks = TextChunker.chunk(text, ChunkSpec(mode = ChunkingMode.MARKDOWN))
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].text.startsWith("Deploy\n"))
        assertTrue(chunks[0].text.contains("run rel7.sh"))
    }

    @Test
    fun `a chunk spanning two sections gets no breadcrumb rather than a wrong one`() {
        val text = "# Alpha\n\ntext a\n\n# Beta\n\ntext b"
        val chunks = TextChunker.chunk(text, ChunkSpec(mode = ChunkingMode.MARKDOWN))
        assertEquals(1, chunks.size)
        assertFalse(chunks[0].text.startsWith("Alpha > Beta"))
        assertFalse(chunks[0].text.startsWith("Alpha\n"))
    }

    @Test
    fun `nested headings build a breadcrumb chain`() {
        val spec = ChunkSpec(mode = ChunkingMode.MARKDOWN, maxChars = 200, overlapChars = 0)
        val text = "# Deploy\n\n" + "p".repeat(150) + "\n\n## Release\n\n" + "c".repeat(150)
        val chunks = TextChunker.chunk(text, spec)
        assertTrue(
            "expected a chunk labelled with both levels, got " + chunks.map { it.text.take(24) },
            chunks.any { it.text.startsWith("Deploy > Release\n") },
        )
    }

    @Test
    fun `a heading never ends up alone as a chunk of its own`() {
        // A heading written as its own chunk retrieves nothing: it says "Release", and so does
        // every other section heading in the corpus. The blank line after the heading must not
        // end the piece.
        val spec = ChunkSpec(mode = ChunkingMode.MARKDOWN, maxChars = 400, overlapChars = 0)
        val text = "# Section\n\n" + "content ".repeat(60)
        val chunks = TextChunker.chunk(text, spec)
        assertTrue(chunks[0].text.contains("# Section"))
        assertTrue(chunks[0].text.contains("content content"))
        assertTrue("no chunk may be just a heading", chunks.none { it.text.trim().startsWith("# ") && it.text.trim().lines().size == 1 })
    }

    // --- code --------------------------------------------------------------

    @Test
    fun `code mode splits at top-level declarations`() {
        val text = buildString {
            appendLine("fun alpha() {")
            appendLine("    println(\"a\")".repeat(4))
            appendLine("}")
            appendLine("fun beta() {")
            appendLine("    println(\"b\")".repeat(4))
            appendLine("}")
        }
        val chunks = TextChunker.chunk(text, ChunkSpec(mode = ChunkingMode.CODE, maxChars = 90, overlapChars = 0))
        assertTrue(chunks.size >= 2)
        assertTrue(chunks[0].text.startsWith("fun alpha()"))
        assertTrue(chunks.any { it.text.startsWith("fun beta()") })
    }

    @Test
    fun `code mode keeps a body with its declaration`() {
        // The whole reason for a code mode: cutting between a signature and its body produces two
        // chunks that each retrieve nothing useful.
        val text = "def parse(self, text):\n    return text.split(\"\\n\")"
        val chunks = TextChunker.chunk(text, ChunkSpec(mode = ChunkingMode.CODE))
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].text.contains("def parse"))
        assertTrue(chunks[0].text.contains("return text.split"))
    }

    // --- the model's window -------------------------------------------------

    @Test
    fun `a model with a big enough window keeps the default budget`() {
        // The change has to be a no-op for the models that were already there, or every existing
        // index is invalidated by an update that was supposed to add a model.
        assertEquals(ChunkSpec(), ChunkSpec().fitting(8192))
        assertEquals(ChunkSpec(), ChunkSpec().fitting(32_768))
        assertEquals(1200, ChunkSpec().fitting(32_768).maxChars)
    }

    @Test
    fun `an unknown window keeps the default rather than guessing`() {
        assertEquals(ChunkSpec(), ChunkSpec().fitting(0))
        assertEquals(ChunkSpec(), ChunkSpec().fitting(-1))
    }

    @Test
    fun `a small window shrinks the budget and the overlap together`() {
        // 512 tokens is bge-small-zh-v1.5. Half of it in characters, because a character of
        // Chinese can be a token.
        val fitted = ChunkSpec().fitting(512)
        assertEquals(256, fitted.maxChars)
        assertEquals(42, fitted.overlapChars)
        assertTrue(fitted.overlapChars < fitted.maxChars)
    }

    @Test
    fun `fitting never grows a budget someone set smaller`() {
        val small = ChunkSpec(maxChars = 100, overlapChars = 10)
        assertEquals(100, small.fitting(8192).maxChars)
        assertEquals(100, small.fitting(0).maxChars)
    }

    @Test
    fun `chunks fit the window they were sized for`() {
        // The invariant that matters downstream: every chunk is inside the budget, because the
        // runtime refuses a text that is not, and a refused chunk is a document that never gets
        // indexed. Four hundred short paragraphs is a note of the length that used to produce a
        // chunk no 512-token model could take.
        val text = (1..400).joinToString("\n\n") { "第 $it 段：向量检索把文档切成小块再嵌入，这是第 $it 段的内容。" }
        val spec = ChunkSpec(mode = ChunkingMode.PLAIN, headingBreadcrumb = false).fitting(512)
        val chunks = TextChunker.chunk(text, spec)

        assertTrue("expected more than one chunk", chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue("chunk of ${chunk.text.length} chars exceeds the budget", chunk.text.length <= spec.maxChars)
        }
        // Nothing was dropped: the packing is a partition, not a truncation.
        assertTrue(chunks.any { it.text.contains("第 400 段") })
    }
}
