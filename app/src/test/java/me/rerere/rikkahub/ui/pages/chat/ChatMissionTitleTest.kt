package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「以这条消息开始任务」时，标题从目标里推出来。
 *
 * 一条几百字的消息直接当标题，会在抽屉 / 工作台的列表里撑成一整句话；而截断不能把标题切成
 * 半个字。
 */
class ChatMissionTitleTest {

    @Test
    fun `first non-blank line becomes the title`() {
        assertEquals("整理第三季度合同", missionTitleFromGoal("\n\n整理第三季度合同\n顺便看看附件"))
    }

    @Test
    fun `long titles are cut with an ellipsis`() {
        val goal = "一".repeat(120)
        val title = missionTitleFromGoal(goal)
        assertEquals(MAX_TITLE_CHARS + 1, title.length)
        assertEquals("…", title.takeLast(1))
    }

    @Test
    fun `blank goal yields no title so the sheet stays empty`() {
        assertEquals("", missionTitleFromGoal(""))
        assertEquals("", missionTitleFromGoal("   \n\t "))
    }
}
