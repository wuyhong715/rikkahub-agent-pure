package me.rerere.rikkahub.service.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 — [MissionContinueComposer] formatting: section presence, truncation rules
 * (artifacts > 20, tail > 800 chars), empty-list handling and the round line.
 * Pure string building, bare-JVM test.
 */
class MissionContinueComposerTest {

    private fun ctx(
        title: String = "整理相册",
        goal: String = "把相册里的截图按应用归类",
        goalsChecked: List<String> = listOf("扫描截图", "归档到文件夹"),
        artifacts: List<String> = listOf("/workspace/out/index.md"),
        lastRunTail: String? = "上一轮扫描了 120 张截图，接下来归档。",
        round: Int = 2,
        maxRounds: Int = 6,
    ) = HandoffContext(
        title = title,
        goal = goal,
        goalsChecked = goalsChecked,
        artifacts = artifacts,
        lastRunTail = lastRunTail,
        round = round,
        maxRounds = maxRounds,
    )

    @Test
    fun `the handoff starts with the supervisor marker and carries every section`() {
        val text = MissionContinueComposer.compose(ctx())
        assertTrue(text.startsWith("[任务督导]"))
        assertTrue(text.contains("上一轮未完成"))
        assertTrue(text.contains("任务：整理相册"))
        assertTrue(text.contains("目标：把相册里的截图按应用归类"))
        assertTrue(text.contains("目标分解（未完成项）："))
        assertTrue(text.contains("- 扫描截图"))
        assertTrue(text.contains("- 归档到文件夹"))
        assertTrue(text.contains("已产出清单："))
        assertTrue(text.contains("- /workspace/out/index.md"))
        assertTrue(text.contains("上一轮结尾："))
        assertTrue(text.contains("上一轮扫描了 120 张截图，接下来归档。"))
        assertTrue(text.contains("这是第 2/6 轮。"))
        assertTrue(text.contains("不要重做"))
        assertTrue(text.contains("mission_report(done)"))
        assertTrue(text.contains("mission_report(blocked)"))
    }

    @Test
    fun `no artifacts yet renders an explicit placeholder`() {
        val text = MissionContinueComposer.compose(ctx(artifacts = emptyList()))
        assertTrue(text.contains("已产出清单：（暂无）"))
        assertFalse(text.contains("…等"))
    }

    @Test
    fun `no goal breakdown omits the section`() {
        val text = MissionContinueComposer.compose(ctx(goalsChecked = emptyList()))
        assertFalse(text.contains("目标分解"))
    }

    @Test
    fun `artifacts past twenty are truncated and the total is announced`() {
        val artifacts = (1..25).map { "/workspace/out/artifact-$it.txt" }
        val text = MissionContinueComposer.compose(ctx(artifacts = artifacts))
        assertEquals(20, Regex("/workspace/out/artifact-").findAll(text).count())
        assertTrue(text.contains("/workspace/out/artifact-20.txt"))
        assertFalse(text.contains("artifact-21"))
        assertTrue(text.contains("…等 25 件"))
    }

    @Test
    fun `exactly twenty artifacts are all listed without a marker`() {
        val artifacts = (1..20).map { "/workspace/out/artifact-$it.txt" }
        val text = MissionContinueComposer.compose(ctx(artifacts = artifacts))
        assertFalse(text.contains("…等"))
        assertEquals(20, Regex("/workspace/out/artifact-").findAll(text).count())
    }

    @Test
    fun `an overlong run tail is capped at eight hundred characters`() {
        val pattern = "0123456789"
        val text = MissionContinueComposer.compose(ctx(lastRunTail = pattern.repeat(90)))
        assertTrue(text.contains(pattern.repeat(80)))
        assertFalse(text.contains(pattern.repeat(81)))
    }

    @Test
    fun `a null run tail omits the section`() {
        val text = MissionContinueComposer.compose(ctx(lastRunTail = null))
        assertFalse(text.contains("上一轮结尾"))
    }

    @Test
    fun `a blank run tail omits the section`() {
        val text = MissionContinueComposer.compose(ctx(lastRunTail = "   "))
        assertFalse(text.contains("上一轮结尾"))
    }

    @Test
    fun `the directive spells out mission mode and the reporting protocol`() {
        val text = MissionContinueComposer.composeDirective(
            title = "整理相册",
            goal = "把相册里的截图按应用归类",
            goals = listOf("扫描截图", "归档到文件夹"),
        )
        assertTrue(text.startsWith("[任务模式]"))
        assertTrue(text.contains("你现在处于任务模式"))
        assertTrue(text.contains("任务：整理相册"))
        assertTrue(text.contains("目标：把相册里的截图按应用归类"))
        assertTrue(text.contains("目标分解："))
        assertTrue(text.contains("- 扫描截图"))
        assertTrue(text.contains("- 归档到文件夹"))
        assertTrue(text.contains("工作区"))
        assertTrue(text.contains("mission_report(done)"))
        assertTrue(text.contains("交付摘要"))
        assertTrue(text.contains("mission_report(blocked)"))
        assertTrue(text.contains("提醒你继续"))
    }

    @Test
    fun `the directive skips the breakdown when there are no goals`() {
        val text = MissionContinueComposer.composeDirective(
            title = "t",
            goal = "g",
            goals = emptyList(),
        )
        assertFalse(text.contains("目标分解"))
    }

    @Test
    fun `the rework prompt carries the change request and keeps the signal contract`() {
        val text = MissionContinueComposer.composeRework("图表要按周分组")

        assertTrue(text.startsWith("[验收打回]"))
        assertTrue(text.contains("图表要按周分组"))
        assertTrue(text.contains("mission_report(done)"))
        assertFalse(
            "a filled change request must not also print the empty fallback",
            text.contains("未填写"),
        )
    }

    @Test
    fun `the rework prompt falls back to a self-check when feedback is blank`() {
        val text = MissionContinueComposer.composeRework("   ")

        assertTrue(text.contains("未填写"))
        assertTrue(text.contains("验收标准"))
    }
}
