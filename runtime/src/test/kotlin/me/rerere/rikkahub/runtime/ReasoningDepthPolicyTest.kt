package me.rerere.rikkahub.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the one narrowing the policy performs and — more importantly — every case it must
 * leave alone. The expensive mistake here is suppressing thinking on a turn that needed it,
 * not the other way round.
 */
class ReasoningDepthPolicyTest {

    private fun user(text: String = "do the thing") =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun assistantWith(vararg calls: UIMessagePart.Tool) =
        UIMessage(role = MessageRole.ASSISTANT, parts = calls.toList())

    private fun okCall(id: String = "c1", output: String = """{"content":"x"}""") =
        UIMessagePart.Tool(
            toolCallId = id,
            toolName = "read_file",
            input = """{"path":"/a"}""",
            output = listOf(UIMessagePart.Text(output)),
        )

    private fun pendingCall(id: String = "c1") =
        UIMessagePart.Tool(
            toolCallId = id,
            toolName = "write_file",
            input = """{"path":"/a"}""",
        )

    @Test
    fun `auto narrows to off on a successful tool follow-up`() {
        val messages = listOf(user(), assistantWith(okCall()))

        assertEquals(
            ReasoningLevel.OFF,
            ReasoningDepthPolicy.forRequest(ReasoningLevel.AUTO, messages),
        )
    }

    @Test
    fun `an explicit level is never overridden`() {
        val messages = listOf(user(), assistantWith(okCall()))
        val explicit = listOf(
            ReasoningLevel.OFF,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
        )

        explicit.forEach { level ->
            assertEquals(level, ReasoningDepthPolicy.forRequest(level, messages))
        }
    }

    @Test
    fun `a user turn keeps auto`() {
        assertEquals(
            ReasoningLevel.AUTO,
            ReasoningDepthPolicy.forRequest(ReasoningLevel.AUTO, listOf(user())),
        )
    }

    @Test
    fun `an assistant turn without tool calls keeps auto`() {
        val messages = listOf(
            user(),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("done"))),
        )

        assertEquals(
            ReasoningLevel.AUTO,
            ReasoningDepthPolicy.forRequest(ReasoningLevel.AUTO, messages),
        )
    }

    @Test
    fun `a call still waiting for approval keeps auto`() {
        val messages = listOf(user(), assistantWith(pendingCall()))

        assertEquals(
            ReasoningLevel.AUTO,
            ReasoningDepthPolicy.forRequest(ReasoningLevel.AUTO, messages),
        )
    }

    @Test
    fun `a failed tool keeps auto because the model has to re-plan`() {
        val messages = listOf(
            user(),
            assistantWith(okCall("c1"), okCall("c2", output = """{"error":"timeout"}""")),
        )

        assertEquals(
            ReasoningLevel.AUTO,
            ReasoningDepthPolicy.forRequest(ReasoningLevel.AUTO, messages),
        )
    }

    @Test
    fun `an empty conversation keeps auto`() {
        assertEquals(
            ReasoningLevel.AUTO,
            ReasoningDepthPolicy.forRequest(ReasoningLevel.AUTO, emptyList()),
        )
    }
}
