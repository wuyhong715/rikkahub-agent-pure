package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLibraryRulesTest {

    private fun candidate(
        path: String,
        kind: LibraryFileKind = LibraryFileKind.TEXT,
        sizeBytes: Long = 1_000,
    ) = WorkspaceLibraryRules.Candidate(
        path = path,
        name = path.substringAfterLast('/'),
        kind = kind,
        sizeBytes = sizeBytes,
    )

    @Test
    fun `kind follows the extension allow-list`() {
        assertEquals(LibraryFileKind.TEXT, WorkspaceLibraryRules.kindOf("notes.md"))
        assertEquals(LibraryFileKind.TEXT, WorkspaceLibraryRules.kindOf("notes.MD"))
        assertEquals(LibraryFileKind.TEXT, WorkspaceLibraryRules.kindOf("data.csv"))
        assertEquals(LibraryFileKind.CODE, WorkspaceLibraryRules.kindOf("Main.kt"))
        assertEquals(LibraryFileKind.CODE, WorkspaceLibraryRules.kindOf("run.SH"))
        assertEquals(LibraryFileKind.DOCUMENT, WorkspaceLibraryRules.kindOf("paper.pdf"))
        assertEquals(LibraryFileKind.DOCUMENT, WorkspaceLibraryRules.kindOf("Deck.PPTX"))
    }

    @Test
    fun `binaries and unknown extensions are not indexed`() {
        assertNull(WorkspaceLibraryRules.kindOf("libfoo.so"))
        assertNull(WorkspaceLibraryRules.kindOf("cache.jar"))
        assertNull(WorkspaceLibraryRules.kindOf("archive.zip"))
        assertNull(WorkspaceLibraryRules.kindOf("model.gguf"))
        assertNull(WorkspaceLibraryRules.kindOf("binary"))
        // Media that is not text, and whose meaning needs a model of its own.
        assertNull(WorkspaceLibraryRules.kindOf("clip.mp4"))
        assertNull(WorkspaceLibraryRules.kindOf("voice.m4a"))
    }

    @Test
    fun `conventional names without an extension are still indexed`() {
        assertEquals(LibraryFileKind.CODE, WorkspaceLibraryRules.kindOf("Makefile"))
        assertEquals(LibraryFileKind.CODE, WorkspaceLibraryRules.kindOf("Dockerfile"))
        assertEquals(LibraryFileKind.TEXT, WorkspaceLibraryRules.kindOf("README"))
        assertEquals(LibraryFileKind.TEXT, WorkspaceLibraryRules.kindOf("LICENSE"))
    }

    @Test
    fun `documents map to a parser and everything else does not`() {
        assertEquals(LibraryDocumentParser.PDF, WorkspaceLibraryRules.documentParserFor("a.pdf"))
        assertEquals(LibraryDocumentParser.DOCX, WorkspaceLibraryRules.documentParserFor("a.docx"))
        assertEquals(LibraryDocumentParser.PPTX, WorkspaceLibraryRules.documentParserFor("a.pptx"))
        assertEquals(LibraryDocumentParser.EPUB, WorkspaceLibraryRules.documentParserFor("a.epub"))
        assertNull(WorkspaceLibraryRules.documentParserFor("a.md"))
        assertNull(WorkspaceLibraryRules.documentParserFor("a.kt"))
    }

    @Test
    fun `chunk mode follows what the file is`() {
        assertEquals(ChunkingMode.MARKDOWN, WorkspaceLibraryRules.chunkModeOf(LibraryFileKind.TEXT, "a.md"))
        assertEquals(ChunkingMode.PLAIN, WorkspaceLibraryRules.chunkModeOf(LibraryFileKind.TEXT, "a.txt"))
        assertEquals(ChunkingMode.PLAIN, WorkspaceLibraryRules.chunkModeOf(LibraryFileKind.TEXT, "a.csv"))
        assertEquals(ChunkingMode.CODE, WorkspaceLibraryRules.chunkModeOf(LibraryFileKind.CODE, "a.kt"))
        assertEquals(ChunkingMode.CODE, WorkspaceLibraryRules.chunkModeOf(LibraryFileKind.CODE, "Makefile"))
        assertEquals(ChunkingMode.PLAIN, WorkspaceLibraryRules.chunkModeOf(LibraryFileKind.DOCUMENT, "a.pdf"))
        // Recognised text is lines of prose, whatever the image happened to be of.
        assertEquals(ChunkingMode.PLAIN, WorkspaceLibraryRules.chunkModeOf(LibraryFileKind.IMAGE, "screen.png"))
    }

    @Test
    fun `ignored directories are matched at any depth`() {
        assertTrue(WorkspaceLibraryRules.isIgnoredPath("node_modules/a.js"))
        assertTrue(WorkspaceLibraryRules.isIgnoredPath("pkg/node_modules/a.js"))
        assertTrue(WorkspaceLibraryRules.isIgnoredPath(".git/config"))
        assertTrue(WorkspaceLibraryRules.isIgnoredPath("src/.cache/x.json"))
        assertTrue(WorkspaceLibraryRules.isIgnoredPath("build/output.txt"))
        assertFalse(WorkspaceLibraryRules.isIgnoredPath("src/Main.kt"))
        assertFalse(WorkspaceLibraryRules.isIgnoredPath("notes.md"))
        // A name that merely starts with an ignored word is not an ignored directory.
        assertFalse(WorkspaceLibraryRules.isIgnoredPath("builder/notes.md"))
    }

    @Test
    fun `a round reads files the index has never seen first`() {
        val candidates = listOf(
            candidate("c.md"),
            candidate("a.md"),
            candidate("b.md"),
        )
        val round = WorkspaceLibraryRules.planRound(candidates, indexed = setOf("a.md"))
        assertEquals(listOf("b.md", "c.md", "a.md"), round.take.map { it.path })
        assertEquals(0, round.deferred)
    }

    @Test
    fun `a round stops when the budget is spent and defers the rest`() {
        // Each text file estimates at its byte size, so three 400k files and an 800k budget fit
        // exactly two.
        val candidates = listOf(
            candidate("a.md", sizeBytes = 400_000),
            candidate("b.md", sizeBytes = 400_000),
            candidate("c.md", sizeBytes = 400_000),
        )
        val round = WorkspaceLibraryRules.planRound(
            candidates,
            indexed = emptySet(),
            budgetChars = 800_000,
        )
        assertEquals(listOf("a.md", "b.md"), round.take.map { it.path })
        assertEquals(1, round.deferred)
    }

    @Test
    fun `an empty round never happens because of one large file`() {
        val round = WorkspaceLibraryRules.planRound(
            listOf(candidate("big.md", sizeBytes = 400_000)),
            indexed = emptySet(),
            budgetChars = 1_000,
        )
        assertEquals(listOf("big.md"), round.take.map { it.path })
    }

    @Test
    fun `the file count bounds a round of tiny files`() {
        val candidates = (1..10).map { candidate("f$it.md", sizeBytes = 10) }
        val round = WorkspaceLibraryRules.planRound(candidates, indexed = emptySet(), maxFiles = 3)
        assertEquals(3, round.take.size)
        assertEquals(7, round.deferred)
    }

    @Test
    fun `files over their cap are reported rather than read`() {
        val candidates = listOf(
            candidate("huge.md", sizeBytes = WorkspaceLibraryRules.MAX_TEXT_FILE_BYTES + 1),
            candidate("huge.pdf", kind = LibraryFileKind.DOCUMENT, sizeBytes = WorkspaceLibraryRules.MAX_DOCUMENT_FILE_BYTES + 1),
            candidate("fine.md", sizeBytes = 1_000),
        )
        val round = WorkspaceLibraryRules.planRound(candidates, indexed = emptySet())
        assertEquals(listOf("fine.md"), round.take.map { it.path })
        assertEquals(listOf("huge.md", "huge.pdf"), round.tooBig)
        assertEquals(0, round.deferred)
    }

    @Test
    fun `estimates are capped by the kind's own limit`() {
        assertEquals(
            WorkspaceLibraryRules.MAX_TEXT_CHARS,
            WorkspaceLibraryRules.estimatedChars(LibraryFileKind.TEXT, 50_000_000),
        )
        // A document's estimate is a ratio of its size, capped at the extraction limit.
        assertEquals(
            WorkspaceLibraryRules.MAX_DOCUMENT_CHARS,
            WorkspaceLibraryRules.estimatedChars(LibraryFileKind.DOCUMENT, 500_000_000),
        )
        assertEquals(2_000, WorkspaceLibraryRules.estimatedChars(LibraryFileKind.DOCUMENT, 8_000))
        assertEquals(8_000, WorkspaceLibraryRules.estimatedChars(LibraryFileKind.CODE, 8_000))
    }

    @Test
    fun `a file this round deferred is not forgotten`() {
        // The bug this pins: a round reads part of the library, and the part it did not read must
        // keep its rows - otherwise every round deletes the previous round's work.
        val indexed = listOf("a.md", "b.md", "c.md")
        val seen = setOf("a.md", "b.md", "c.md", "d.md")
        assertEquals(emptyList<String>(), WorkspaceLibraryRules.toForget(indexed, seen))
    }

    @Test
    fun `only a file the walk no longer mentions is forgotten`() {
        val indexed = listOf("a.md", "gone.md")
        val seen = setOf("a.md", "b.md")
        assertEquals(listOf("gone.md"), WorkspaceLibraryRules.toForget(indexed, seen))
    }

    @Test
    fun `the default directory is a relative path inside the workspace`() {
        assertEquals("library", WorkspaceLibraryRules.DEFAULT_DIR)
        assertFalse(WorkspaceLibraryRules.DEFAULT_DIR.startsWith("/"))
    }

    @Test
    fun `images are indexed, whatever format the screenshot arrived in`() {
        assertEquals(LibraryFileKind.IMAGE, WorkspaceLibraryRules.kindOf("screen.png"))
        assertEquals(LibraryFileKind.IMAGE, WorkspaceLibraryRules.kindOf("Shot.PNG"))
        assertEquals(LibraryFileKind.IMAGE, WorkspaceLibraryRules.kindOf("photo.jpg"))
        assertEquals(LibraryFileKind.IMAGE, WorkspaceLibraryRules.kindOf("photo.jpeg"))
        assertEquals(LibraryFileKind.IMAGE, WorkspaceLibraryRules.kindOf("scan.webp"))
        assertEquals(LibraryFileKind.IMAGE, WorkspaceLibraryRules.kindOf("IMG_0001.HEIC"))
    }

    @Test
    fun `an image is charged the same whatever it weighs`() {
        // The bug this pins: charging an image by its byte count - the way text is charged - spends
        // most of a round's budget on the first photograph of a folder full of them.
        assertEquals(
            WorkspaceLibraryRules.ESTIMATED_CHARS_PER_IMAGE,
            WorkspaceLibraryRules.estimatedChars(LibraryFileKind.IMAGE, 12_000_000),
        )
        assertEquals(
            WorkspaceLibraryRules.ESTIMATED_CHARS_PER_IMAGE,
            WorkspaceLibraryRules.estimatedChars(LibraryFileKind.IMAGE, 400_000),
        )
        assertTrue(
            WorkspaceLibraryRules.ESTIMATED_CHARS_PER_IMAGE < WorkspaceLibraryRules.MAX_CHARS_PER_ROUND,
        )
    }

    @Test
    fun `a round recognises only so many images`() {
        // Zero-padded so that the planner's path order is also the numeric order; without it the
        // round would legitimately keep shot10 before shot2, and the test would be asserting the
        // compiler's string comparison rather than the cap.
        val candidates = (1..10).map {
            candidate("shot%02d.png".format(it), kind = LibraryFileKind.IMAGE, sizeBytes = 500_000)
        }
        val round = WorkspaceLibraryRules.planRound(candidates, indexed = emptySet(), maxImages = 3)
        assertEquals(listOf("shot01.png", "shot02.png", "shot03.png"), round.take.map { it.path })
        assertEquals(7, round.deferred)
    }

    @Test
    fun `the image cap never pushes out the files that are not images`() {
        val candidates = (1..5).map {
            candidate("shot$it.png", kind = LibraryFileKind.IMAGE, sizeBytes = 500_000)
        } + candidate("notes.md")
        val round = WorkspaceLibraryRules.planRound(candidates, indexed = emptySet(), maxImages = 1)
        assertEquals(listOf("notes.md", "shot1.png"), round.take.map { it.path })
        assertEquals(4, round.deferred)
    }

    @Test
    fun `an image over its cap is reported rather than decoded`() {
        val round = WorkspaceLibraryRules.planRound(
            listOf(
                candidate(
                    "huge.png",
                    kind = LibraryFileKind.IMAGE,
                    sizeBytes = WorkspaceLibraryRules.MAX_IMAGE_FILE_BYTES + 1,
                ),
            ),
            indexed = emptySet(),
        )
        assertEquals(emptyList<String>(), round.take.map { it.path })
        assertEquals(listOf("huge.png"), round.tooBig)
    }

    @Test
    fun `an image with no text in it is indexed as its own name`() {
        // Otherwise the file never enters the index, and every round recognises it again.
        assertEquals("Image: IMG_0001.jpg", WorkspaceLibraryRules.placeholderForImage("IMG_0001.jpg"))
        assertTrue(
            WorkspaceLibraryRules.placeholderForImage("a.png")
                .startsWith(WorkspaceLibraryRules.IMAGE_PLACEHOLDER_PREFIX),
        )
    }
}
