package me.rerere.rikkahub.data.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批 H — 验收条件的清洗、可判定性与提示词块。 */
class AcceptanceCheckSpecTest {

    @Test
    fun `empty checks are dropped`() {
        val cleaned = AcceptanceCheckSpec.sanitize(
            listOf(
                AcceptanceCheck(text = "", kind = AcceptanceKind.MANUAL),
                AcceptanceCheck(text = "报告存在", kind = AcceptanceKind.FILE_EXISTS, target = "report.md"),
            )
        )
        assertEquals(1, cleaned.size)
        assertEquals("报告存在", cleaned.single().text)
    }

    @Test
    fun `text falls back to the target when the label is blank`() {
        val cleaned = AcceptanceCheckSpec.sanitize(
            listOf(AcceptanceCheck(text = "  ", kind = AcceptanceKind.COMMAND, target = "pytest -q"))
        )
        assertEquals("pytest -q", cleaned.single().text)
    }

    @Test
    fun `command without a target is dropped`() {
        val cleaned = AcceptanceCheckSpec.sanitize(
            listOf(AcceptanceCheck(text = "跑测试", kind = AcceptanceKind.COMMAND, target = " "))
        )
        assertTrue(cleaned.isEmpty())
    }

    @Test
    fun `timeouts are clamped`() {
        val cleaned = AcceptanceCheckSpec.sanitize(
            listOf(
                AcceptanceCheck(text = "a", kind = AcceptanceKind.COMMAND, target = "x", timeoutSec = 0),
                AcceptanceCheck(text = "b", kind = AcceptanceKind.COMMAND, target = "y", timeoutSec = 99_999),
            )
        )
        assertEquals(AcceptanceCheckSpec.MIN_TIMEOUT_SEC, cleaned[0].timeoutSec)
        assertEquals(AcceptanceCheckSpec.MAX_TIMEOUT_SEC, cleaned[1].timeoutSec)
    }

    @Test
    fun `manual checks survive but are not machine checkable`() {
        val cleaned = AcceptanceCheckSpec.sanitize(
            listOf(AcceptanceCheck(text = "语气像人写的", kind = AcceptanceKind.MANUAL))
        )
        assertEquals(1, cleaned.size)
        assertFalse(cleaned.single().machineCheckable)
    }

    @Test
    fun `list is capped`() {
        val many = (1..50).map {
            AcceptanceCheck(text = "条件$it", kind = AcceptanceKind.FILE_EXISTS, target = "f$it")
        }
        assertEquals(AcceptanceCheckSpec.MAX_CHECKS, AcceptanceCheckSpec.sanitize(many).size)
    }

    @Test
    fun `min bytes needs a positive threshold`() {
        val check = AcceptanceCheck(
            text = "报告不是空的",
            kind = AcceptanceKind.MIN_BYTES,
            target = "report.md",
            value = 0L,
        )
        assertFalse(check.machineCheckable)
    }

    @Test
    fun `prompt block lists every check with its judgement method`() {
        val block = AcceptanceCheckSpec.composePromptBlock(
            listOf(
                AcceptanceCheck(text = "测试通过", kind = AcceptanceKind.COMMAND, target = "pytest -q"),
                AcceptanceCheck(text = "有总结", kind = AcceptanceKind.FILE_EXISTS, target = "SUMMARY.md"),
            )
        )
        assertTrue(block.contains("验收条件"))
        assertTrue(block.contains("pytest -q"))
        assertTrue(block.contains("SUMMARY.md"))
    }

    @Test
    fun `editor lines parse into typed checks`() {
        val checks = AcceptanceCheckSpec.parseEditorLines(
            """
            命令: pytest -q
            文件: report.md
            包含: report.md | 结论
            不含: report.md | TODO
            大小: report.md | 1024
            语气自然
            # 注释行
            """.trimIndent()
        )
        assertEquals(6, checks.size)
        assertEquals(AcceptanceKind.COMMAND, checks[0].kind)
        assertEquals("pytest -q", checks[0].target)
        assertEquals(AcceptanceKind.FILE_CONTAINS, checks[2].kind)
        assertEquals("report.md", checks[2].path)
        assertEquals("结论", checks[2].target)
        assertEquals(AcceptanceKind.FILE_NOT_CONTAINS, checks[3].kind)
        assertEquals(AcceptanceKind.MIN_BYTES, checks[4].kind)
        assertEquals(1024L, checks[4].value)
        assertEquals("写错的普通文本至少留成人工项", AcceptanceKind.MANUAL, checks[5].kind)
    }

    @Test
    fun `editor round-trips without losing the judgement method`() {
        val original = listOf(
            AcceptanceCheck(text = "测试", kind = AcceptanceKind.COMMAND, target = "pytest -q"),
            AcceptanceCheck(text = "有报告", kind = AcceptanceKind.FILE_EXISTS, target = "report.md"),
            AcceptanceCheck(text = "含结论", kind = AcceptanceKind.FILE_CONTAINS, path = "report.md", target = "结论"),
            AcceptanceCheck(text = "大小", kind = AcceptanceKind.MIN_BYTES, target = "report.md", value = 2048L),
        )
        val reparsed = AcceptanceCheckSpec.parseEditorLines(AcceptanceCheckSpec.toEditorLines(original))
        assertEquals(original.size, reparsed.size)
        original.zip(reparsed).forEach { (a, b) ->
            assertEquals(a.kind, b.kind)
            assertEquals(a.target, b.target)
            assertEquals(a.path, b.path)
            assertEquals(a.value, b.value)
        }
    }

    @Test
    fun `describe includes the target for humans`() {
        val check = AcceptanceCheck(
            text = "有总结",
            kind = AcceptanceKind.FILE_EXISTS,
            target = "SUMMARY.md",
        )
        assertTrue(check.describe().contains("SUMMARY.md"))
    }
}
