package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowLimitsTest {

    private fun conversation(count: Int): List<UIMessage> = (0 until count).map { index ->
        if (index % 2 == 0) UIMessage.user("user $index") else UIMessage.assistant("assistant $index")
    }

    private fun summaryMessage(text: String = "[Summary of previous conversation]") =
        UIMessage.user(text).copy(isSynthetic = true)

    @Test
    fun `limit disabled returns the list untouched`() {
        val messages = conversation(20)
        assertEquals(messages, messages.limitContextPreservingLeadingSynthetic(0))
        assertEquals(messages, messages.limitContextPreservingLeadingSynthetic(-1))
    }

    @Test
    fun `list within the limit returns the list untouched`() {
        val messages = conversation(5)
        assertEquals(messages, messages.limitContextPreservingLeadingSynthetic(5))
        assertEquals(messages, messages.limitContextPreservingLeadingSynthetic(10))
    }

    @Test
    fun `no synthetic prefix behaves exactly like limitContext`() {
        val messages = conversation(40)
        for (limit in 1..15) {
            assertEquals(
                "limit=$limit",
                messages.limitContext(limit),
                messages.limitContextPreservingLeadingSynthetic(limit),
            )
        }
    }

    @Test
    fun `leading summary survives the limit and the tail is still capped`() {
        val summary = summaryMessage()
        val messages = listOf(summary) + conversation(30)
        val result = messages.limitContextPreservingLeadingSynthetic(8)
        assertEquals(summary, result.first())
        assertTrue("result size ${result.size}", result.size <= 8)
        assertEquals(messages.takeLast(result.size - 1), result.drop(1))
    }

    @Test
    fun `all synthetic list is left alone`() {
        val messages = (0 until 30).map { summaryMessage("summary $it") }
        assertEquals(messages, messages.limitContextPreservingLeadingSynthetic(5))
    }

    @Test
    fun `limit of one still leaves one tail message`() {
        val summary = summaryMessage()
        val messages = listOf(summary) + conversation(10)
        val result = messages.limitContextPreservingLeadingSynthetic(1)
        assertEquals(2, result.size)
        assertEquals(summary, result.first())
        assertEquals(messages.last(), result.last())
    }
}
