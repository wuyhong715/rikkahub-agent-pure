package me.rerere.rikkahub.runtime.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what micro-compaction may and may not touch. The expensive mistake is compacting a
 * result the model still needs, so the negative cases are the ones worth guarding.
 *
 * Most cases pass `triggerTokens = 1_000` so a handful of small results is enough to arm the
 * projection; the default trigger has its own test.
 */
class MicroCompactionTest {

    /** Roughly [tokens] estimated tokens of ASCII (the estimator is chars / 3). */
    private fun payload(tokens: Int): String = "x".repeat(tokens * 3)

    private fun tool(
        id: String,
        tokens: Int = 2_500,
        name: String = "read_file",
        body: String = payload(tokens),
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = """{"path":"/$id"}""",
        output = listOf(UIMessagePart.Text(body)),
    )

    private fun assistant(vararg tools: UIMessagePart.Tool) =
        UIMessage(role = MessageRole.ASSISTANT, parts = tools.toList())

    private fun user(text: String = "go") =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun toolsOf(messages: List<UIMessage>): List<UIMessagePart.Tool> =
        messages.flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }

    private fun compacted(
        messages: List<UIMessage>,
        keep: Int,
        trigger: Int = 1_000,
        spill: (String, String) -> String? = { _, _ -> null },
    ): List<UIMessagePart.Tool> = toolsOf(
        MicroCompaction.project(
            messages,
            keepRecentCalls = keep,
            triggerTokens = trigger,
            spill = spill,
        )
    )

    @Test
    fun `a request below the default trigger is left alone`() {
        val messages = listOf(user(), assistant(tool("a"), tool("b"), tool("c")))

        assertEquals(messages, MicroCompaction.project(messages))
    }

    @Test
    fun `the newest calls keep their full output`() {
        val messages = listOf(
            user(),
            assistant(
                tool("a"), tool("b"), tool("c"), tool("d"),
                tool("e"), tool("f"), tool("g"),
            ),
        )

        val tools = compacted(messages, keep = 2)

        (0..4).forEach { index -> assertTrue("call $index", MicroCompaction.isCompacted(tools[index])) }
        assertFalse(MicroCompaction.isCompacted(tools[5]))
        assertFalse(MicroCompaction.isCompacted(tools[6]))
        assertEquals(payload(2_500), (tools[6].output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `a result smaller than the floor is not worth a notice`() {
        val messages = listOf(
            user(),
            assistant(tool("a", tokens = 200), tool("b", tokens = 200), tool("c", tokens = 200)),
        )

        // 600 tokens total, each result far below MIN_RESULT_TOKENS: nothing to gain.
        val tools = compacted(messages, keep = 1, trigger = 100)

        tools.forEach { assertFalse(MicroCompaction.isCompacted(it)) }
    }

    @Test
    fun `a failed call keeps its error for re-planning`() {
        val messages = listOf(
            user(),
            assistant(
                tool("a", body = """{"error":"timeout"}"""),
                tool("b"),
                tool("c"),
            ),
        )

        val tools = compacted(messages, keep = 1)

        assertFalse(MicroCompaction.isCompacted(tools[0]))
        assertTrue(MicroCompaction.isCompacted(tools[1]))
    }

    @Test
    fun `a tool_output_read page is never compacted`() {
        val messages = listOf(
            user(),
            assistant(
                tool("a", name = ToolOutputRules.READ_TOOL_NAME),
                tool("b"),
                tool("c"),
            ),
        )

        val tools = compacted(messages, keep = 1)

        assertFalse(MicroCompaction.isCompacted(tools[0]))
        assertTrue(MicroCompaction.isCompacted(tools[1]))
    }

    @Test
    fun `the notice points at the spilled file when one exists`() {
        val messages = listOf(user(), assistant(tool("a"), tool("b"), tool("c")))

        val tools = compacted(messages, keep = 1) { id, _ -> "$id.txt" }

        val notice = (tools[0].output.single() as UIMessagePart.Text).text
        assertTrue(notice.contains("/tool_outputs/a.txt"))
        assertTrue(notice.contains(ToolOutputRules.READ_TOOL_NAME))
    }

    @Test
    fun `without a spill the notice tells the model to re-run`() {
        val messages = listOf(user(), assistant(tool("a"), tool("b"), tool("c")))

        val tools = compacted(messages, keep = 1)

        val notice = (tools[0].output.single() as UIMessagePart.Text).text
        assertTrue(notice.contains("re-run the call"))
    }

    @Test
    fun `projection is idempotent`() {
        val messages = listOf(user(), assistant(tool("a"), tool("b"), tool("c")))

        val once = MicroCompaction.project(messages, keepRecentCalls = 1, triggerTokens = 1_000)
        val twice = MicroCompaction.project(once, keepRecentCalls = 1, triggerTokens = 1_000)

        assertEquals(once, twice)
    }

    @Test
    fun `the call record survives the compaction`() {
        val messages = listOf(user(), assistant(tool("a"), tool("b"), tool("c")))

        val tools = compacted(messages, keep = 1)

        assertEquals("a", tools[0].toolCallId)
        assertEquals("read_file", tools[0].toolName)
        assertEquals("""{"path":"/a"}""", tools[0].input)
    }

    @Test
    fun `a spilled result is only written once per call`() {
        val messages = listOf(user(), assistant(tool("a"), tool("b")))
        var writes = 0
        val spill = { id: String, _: String ->
            writes++
            "$id.txt"
        }

        MicroCompaction.project(messages, keepRecentCalls = 1, triggerTokens = 1_000, spill = spill)

        // One eligible call ("a"), one write: the projection never spills the same call twice.
        assertEquals(1, writes)
    }
}
