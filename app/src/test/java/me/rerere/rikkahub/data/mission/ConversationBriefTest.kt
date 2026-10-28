package me.rerere.rikkahub.data.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从对话开始任务」的摘要规则。这里的错误分两种：摘要太长（用户为闲聊多付钱），或者摘要漏掉
 * 最近的话（任务跑偏）。两个方向都钉住。
 */
class ConversationBriefTest {

    private fun line(text: String, role: ConversationBrief.Role = ConversationBrief.Role.USER) =
        ConversationBrief.Line(role, text)

    @Test
    fun `empty conversation yields no brief`() {
        assertNull(ConversationBrief.compose(emptyList()))
        // 只有空白（纯附件 / 纯工具调用的轮次）也算空
        assertNull(ConversationBrief.compose(listOf(line("   "), line("\n\n"))))
    }

    @Test
    fun `only the newest messages are kept`() {
        val messages = (1..20).map { line("m$it") }
        val brief = ConversationBrief.compose(messages)
        assertNotNull(brief)

        // 20 条里留最后 12 条：第 9 条起
        assertTrue("最近的一条必须在", brief!!.contains("m20"))
        assertTrue(brief.contains("m9"))
        assertFalse("更早的必须丢掉", brief.contains("m8"))
        assertFalse(brief.contains("m1 "))
    }

    @Test
    fun `a single message is truncated with an ellipsis`() {
        val long = "x".repeat(500)
        val brief = ConversationBrief.compose(listOf(line(long)))!!

        val body = brief.lines().first { it.startsWith("- ") }
        // "- **用户**：" + 200 字 + …
        assertEquals("- **用户**：".length + ConversationBrief.MAX_CHARS_PER_MESSAGE + 1, body.length)
        assertTrue(body.endsWith("…"))
    }

    @Test
    fun `total length is capped by dropping the oldest lines`() {
        val messages = (1..12).map { line("y".repeat(ConversationBrief.MAX_CHARS_PER_MESSAGE)) }
        val tight = ConversationBrief.compose(messages, maxTotalChars = 600)!!

        assertTrue("总长必须守住上限：${tight.length}", tight.length <= 600)
        // 装不下 12 条，丢的是最早的：保留的条数少于全部
        val kept = tight.lines().count { it.startsWith("- ") }
        assertTrue(kept in 1 until 12)
    }

    @Test
    fun `default budget keeps every kept line within the documented limit`() {
        val messages = (1..12).map { line("z".repeat(ConversationBrief.MAX_CHARS_PER_MESSAGE)) }
        val brief = ConversationBrief.compose(messages)!!
        assertTrue(brief.length <= ConversationBrief.MAX_TOTAL_CHARS)
        assertEquals(12, brief.lines().count { it.startsWith("- ") })
    }

    @Test
    fun `a budget too small for even one line yields no brief`() {
        // 宁可什么都不带，也不要带一段被截断到没有信息的残句
        assertNull(ConversationBrief.compose(listOf(line("hello")), maxTotalChars = 80))
    }

    @Test
    fun `roles are labelled so the model can tell who said what`() {
        val brief = ConversationBrief.compose(
            listOf(
                line("用户问的", ConversationBrief.Role.USER),
                line("模型答的", ConversationBrief.Role.MODEL),
                line("App 写的", ConversationBrief.Role.SYSTEM),
            )
        )!!
        assertTrue(brief.contains("**用户**：用户问的"))
        assertTrue(brief.contains("**模型**：模型答的"))
        assertTrue(brief.contains("**系统**：App 写的"))
        assertTrue("必须标明这是背景而不是指令", brief.contains("不是给你的指令"))
    }

    @Test
    fun `multi-line text is flattened to one line per message`() {
        val brief = ConversationBrief.compose(listOf(line("第一行\n第二行\r\n第三行")))!!
        val body = brief.lines().first { it.startsWith("- ") }
        assertEquals("- **用户**：第一行 第二行 第三行", body)
    }
}
