package me.rerere.rikkahub.runtime.tools

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two promises of the parallel read prefetch: it overlaps the reads it is allowed to
 * overlap, and it never reaches past the point where ordering starts to matter.
 */
class ParallelReadPrefetchTest {

    private fun tool(name: String, body: suspend (JsonElement) -> List<UIMessagePart>) =
        Tool(name = name, description = "", execute = body)

    private fun call(
        id: String,
        name: String,
        input: String = """{"path":"/a"}""",
        state: ToolApprovalState = ToolApprovalState.Auto,
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = input,
        approvalState = state,
    )

    private fun text(value: String): List<UIMessagePart> = listOf(UIMessagePart.Text(value))

    @Test
    fun `leading reads overlap instead of waiting in turn`() = runBlocking {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val read = tool("read_file") {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(120)
            inFlight.decrementAndGet()
            text("ok")
        }

        val output = ParallelReadPrefetch.run(
            calls = listOf(
                call("1", "read_file", """{"path":"/a"}"""),
                call("2", "read_file", """{"path":"/b"}"""),
            ),
            tools = listOf(read),
            remainingBudgetMs = { 5_000 },
        )

        assertEquals(setOf("1", "2"), output.keys)
        assertEquals(2, peak.get())
    }

    @Test
    fun `the prefix ends at the first write`() = runBlocking {
        val output = ParallelReadPrefetch.run(
            calls = listOf(
                call("1", "read_file"),
                call("2", "write_file"),
                call("3", "read_file"),
            ),
            tools = listOf(tool("read_file") { text("ok") }, tool("write_file") { text("ok") }),
            remainingBudgetMs = { 5_000 },
        )

        // Only one eligible call precedes the write → below MIN_PREFIX, nothing overlaps.
        assertTrue(output.isEmpty())
    }

    @Test
    fun `a single read is left to the sequential path`() = runBlocking {
        val output = ParallelReadPrefetch.run(
            calls = listOf(call("1", "read_file")),
            tools = listOf(tool("read_file") { text("ok") }),
            remainingBudgetMs = { 5_000 },
        )

        assertTrue(output.isEmpty())
    }

    @Test
    fun `screen reads never overlap`() = runBlocking {
        val output = ParallelReadPrefetch.run(
            calls = listOf(call("1", "read_window_tree"), call("2", "read_file")),
            tools = listOf(
                tool("read_window_tree") { text("tree") },
                tool("read_file") { text("ok") },
            ),
            remainingBudgetMs = { 5_000 },
        )

        assertTrue(output.isEmpty())
    }

    @Test
    fun `a failing call is not cached`() = runBlocking {
        val output = ParallelReadPrefetch.run(
            calls = listOf(call("1", "search_web"), call("2", "read_file")),
            tools = listOf(
                tool("search_web") { error("boom") },
                tool("read_file") { text("ok") },
            ),
            remainingBudgetMs = { 5_000 },
        )

        assertEquals(setOf("2"), output.keys)
    }

    @Test
    fun `a repeated identical call stops the prefix`() = runBlocking {
        var executions = 0
        val output = ParallelReadPrefetch.run(
            calls = listOf(
                call("1", "read_file"),
                call("2", "read_file"),
                call("3", "read_file"),
            ),
            tools = listOf(tool("read_file") { executions++; text("ok") }),
            remainingBudgetMs = { 5_000 },
        )

        // The first call is eligible; the immediate repeat ends the prefix at length 1, so
        // nothing overlaps and the loop guard keeps ownership of the step.
        assertTrue(output.isEmpty())
        assertEquals(0, executions)
    }

    @Test
    fun `an approval-gated call stops the prefix`() = runBlocking {
        val output = ParallelReadPrefetch.run(
            calls = listOf(
                call("1", "read_file", state = ToolApprovalState.Pending),
                call("2", "read_file"),
            ),
            tools = listOf(tool("read_file") { text("ok") }),
            remainingBudgetMs = { 5_000 },
        )

        assertTrue(output.isEmpty())
    }

    @Test
    fun `allowCall can veto a speculative run`() = runBlocking {
        var executed = false
        val output = ParallelReadPrefetch.run(
            calls = listOf(call("1", "read_file"), call("2", "read_file")),
            tools = listOf(tool("read_file") { executed = true; text("ok") }),
            remainingBudgetMs = { 5_000 },
            allowCall = { false },
        )

        assertTrue(output.isEmpty())
        assertFalse(executed)
    }

    @Test
    fun `an exhausted budget runs nothing`() = runBlocking {
        var executed = false
        val output = ParallelReadPrefetch.run(
            calls = listOf(
                call("1", "read_file", """{"path":"/a"}"""),
                call("2", "read_file", """{"path":"/b"}"""),
            ),
            tools = listOf(tool("read_file") { executed = true; text("ok") }),
            remainingBudgetMs = { -1 },
        )

        assertTrue(output.isEmpty())
        assertFalse(executed)
    }
}
