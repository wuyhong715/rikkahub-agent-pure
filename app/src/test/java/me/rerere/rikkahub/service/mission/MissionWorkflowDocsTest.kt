package me.rerere.rikkahub.service.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [MissionWorkflowDocs] — the pure half of 批 C (workflow documents).
 *
 * The launcher's IO is covered in [MissionLauncherTest]; here we pin the filtering rules,
 * the truncation marker and the exact prompt wording the model will read.
 */
class MissionWorkflowDocsTest {

    @Test
    fun `only direct markdown files under workflows are workflow docs`() {
        assertTrue(MissionWorkflowDocs.isWorkflowDoc("workflows/weekly.md"))
        assertTrue(MissionWorkflowDocs.isWorkflowDoc("workflows/Weekly.MD"))
        assertTrue(MissionWorkflowDocs.isWorkflowDoc("./workflows/a.md"))

        assertFalse("no directory prefix", MissionWorkflowDocs.isWorkflowDoc("weekly.md"))
        assertFalse("nested folders are not candidates", MissionWorkflowDocs.isWorkflowDoc("workflows/sub/a.md"))
        assertFalse("not markdown", MissionWorkflowDocs.isWorkflowDoc("workflows/a.txt"))
        assertFalse("outside the folder", MissionWorkflowDocs.isWorkflowDoc("docs/a.md"))
        assertFalse("just the folder", MissionWorkflowDocs.isWorkflowDoc("workflows/"))
    }

    @Test
    fun `pathOf joins the workflow folder`() {
        assertEquals("workflows/周报.md", MissionWorkflowDocs.pathOf(" 周报.md "))
    }

    @Test
    fun `long documents are truncated with a visible marker`() {
        val long = "x".repeat(WORKFLOW_MAX_CHARS + 500)
        val trimmed = MissionWorkflowDocs.trimContent(long)

        assertTrue("cut at the cap", trimmed.length < long.length)
        assertTrue("the cut is disclosed", trimmed.contains("已截断"))
        assertEquals(
            "short documents are only trimmed at the edges",
            "abc",
            MissionWorkflowDocs.trimContent("  abc \n"),
        )
    }

    @Test
    fun `the pinned block names the file, delimits the body and defers to the goal`() {
        val block = MissionWorkflowDocs.composePinned("workflows/周报.md", "第一步\n第二步")

        assertTrue(block.contains("workflows/周报.md"))
        assertTrue(block.contains("--- 流程开始 ---"))
        assertTrue(block.contains("第一步"))
        assertTrue(block.contains("以任务目标为准"))
    }

    @Test
    fun `the chooser block is empty without candidates and sorted when present`() {
        assertEquals("", MissionWorkflowDocs.composeChooser(emptyList()))
        assertEquals("", MissionWorkflowDocs.composeChooser(listOf("  ")))

        val block = MissionWorkflowDocs.composeChooser(listOf("b.md", "a.md", "a.md"))
        assertTrue(block.contains("- a.md"))
        assertTrue(block.contains("- b.md"))
        assertEquals("deduplicated", 1, Regex("- a\\.md").findAll(block).count())
        assertTrue(
            "a is listed before b",
            block.indexOf("a.md") < block.indexOf("b.md"),
        )
    }

    @Test
    fun `at most twenty documents are listed`() {
        val many = (1..WORKFLOW_MAX_LISTED + 5).map { "doc$it.md" }
        val block = MissionWorkflowDocs.composeChooser(many)
        assertEquals(WORKFLOW_MAX_LISTED, Regex("^- doc", RegexOption.MULTILINE).findAll(block).count())
    }

    @Test
    fun `the continuation hint is one line`() {
        val hint = MissionWorkflowDocs.composeContinueHint("workflows/周报.md")
        assertTrue(hint.contains("继续沿用"))
        assertTrue(hint.contains("workflows/周报.md"))
        assertFalse("no newline — it is a single reminder line", hint.contains("\n"))
    }

    // ---------------------------------------------------------------- 批 D — authoring helpers

    @Test
    fun `doc names are sanitized into safe workflow paths`() {
        assertEquals("workflows/周报.md", MissionWorkflowDocs.sanitizeDocName("周报"))
        assertEquals("workflows/周报.md", MissionWorkflowDocs.sanitizeDocName("  周报.md  "))
        assertEquals("workflows/weekly-report.md", MissionWorkflowDocs.sanitizeDocName("weekly report"))
        assertEquals("workflows/a-b.md", MissionWorkflowDocs.sanitizeDocName("a/b.md"))
        assertEquals(
            "path traversal cannot escape the workflow directory",
            "workflows/etc-passwd.md",
            MissionWorkflowDocs.sanitizeDocName("../etc/passwd"),
        )
        assertEquals("empty input is rejected", null, MissionWorkflowDocs.sanitizeDocName("   "))
        assertEquals("a bare .md is rejected", null, MissionWorkflowDocs.sanitizeDocName(".md"))
        assertEquals("dots are rejected", null, MissionWorkflowDocs.sanitizeDocName(".."))
    }

    @Test
    fun `the template names the document and keeps the expected sections`() {
        val doc = MissionWorkflowDocs.template("周报流程", "每周五汇总进展")

        assertTrue(doc.startsWith("# 周报流程"))
        assertTrue(doc.contains("每周五汇总进展"))
        listOf("## 目标", "## 输入", "## 步骤", "## 产出", "## 验收", "## 注意事项").forEach {
            assertTrue("missing section: $it", doc.contains(it))
        }
        assertTrue("the conflict rule matches composePinned", doc.contains("以任务目标为准"))
    }
}