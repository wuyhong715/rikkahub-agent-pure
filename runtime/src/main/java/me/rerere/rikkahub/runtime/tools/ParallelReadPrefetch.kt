package me.rerere.rikkahub.runtime.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

/**
 * Runs the **leading run of idempotent read-only tool calls** concurrently.
 *
 * The generation loop used to dispatch every call of a step with `forEach { … }`, awaiting
 * each one before starting the next. A model that batched three independent reads — the
 * normal shape when it investigates anything — therefore paid `3 × T` for work that has no
 * ordering between it. This object takes exactly the calls at the head of the step that are
 * provably safe to overlap, runs them together, and hands the outputs back to the loop,
 * which then reuses them instead of executing the call a second time.
 *
 * Three things keep the shortcut honest:
 *
 * - **Only a prefix, never the whole step.** A write in the middle of the step means every
 *   call after it may depend on that write's effect. Stopping at the first non-eligible call
 *   (rather than collecting eligible calls from the entire list) preserves the model's
 *   ordering exactly.
 * - **Only idempotent reads**, decided by
 *   [ToolExecutionRetryPolicy.isIdempotentReadOnly] — the same rule the retry layer already
 *   uses to decide it may repeat a call. That rule inspects `web_fetch`'s HTTP verb, so a
 *   POST is never overlapped.
 * - **Shared device state is excluded** ([SHARED_STATE_TOOLS]). Two reads of the
 *   accessibility tree buy nothing (the second one sees the same screen) and race inside the
 *   accessibility service, so they stay sequential.
 *
 * Failures are deliberately *not* cached: an exception or a timeout returns `null`, the
 * caller sees no entry for that call and executes it on the normal path, which owns the
 * error envelope and the retry policy. The only cost of a failed prefetch is one extra
 * attempt of a read that already failed.
 */
object ParallelReadPrefetch {

    /** A prefix shorter than this is not worth the coroutine machinery — run it inline. */
    const val MIN_PREFIX: Int = 2

    /** Upper bound on overlapping calls, so a step with ten reads cannot flood a provider. */
    const val MAX_IN_FLIGHT: Int = 4

    /**
     * Tools whose answer is a snapshot of shared, mutable device state. Reading the screen
     * twice in parallel returns the same tree and risks unsynchronised access to the
     * accessibility service; these always run in the caller's sequential order.
     */
    private val SHARED_STATE_TOOLS = setOf(
        "read_window_tree",
        "find_node",
        "screenshot",
        "wait_for",
        "get_screen_state",
        "screen_state",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param calls the step's tool calls, in the order the model emitted them.
     * @param tools the tool definitions available this turn.
     * @param remainingBudgetMs wall-clock left in the turn, sampled per call because the
     *   budget keeps draining while the calls run.
     * @param allowCall consulted for each candidate; a `false` (headless refusal, missing
     *   approval channel) stops the prefix before anything is executed speculatively.
     * @return outputs keyed by `toolCallId`. A missing key means "execute this normally".
     */
    suspend fun run(
        calls: List<UIMessagePart.Tool>,
        tools: List<Tool>,
        remainingBudgetMs: () -> Long,
        allowCall: (toolName: String) -> Boolean = { true },
    ): Map<String, List<UIMessagePart>> {
        val prefix = ArrayList<Pair<UIMessagePart.Tool, Tool>>()
        val argsByCallId = HashMap<String, JsonElement>()
        val seenSignatures = HashSet<String>()

        for (call in calls) {
            val state = call.approvalState
            if (state != ToolApprovalState.Auto && state != ToolApprovalState.Approved) break
            if (call.toolName in SHARED_STATE_TOOLS) break
            if (!allowCall(call.toolName)) break
            // A repeated identical call is the model looping: the loop guard will reject it
            // in the sequential pass, so running it here would only produce an extra external
            // request whose result is then discarded.
            if (!seenSignatures.add(call.toolName + "::" + call.input)) break
            val definition = tools.firstOrNull { it.name == call.toolName } ?: break
            val args = runCatching {
                json.parseToJsonElement(call.input.ifBlank { "{}" })
            }.getOrNull() ?: break
            // Arguments decide for `web_fetch` (GET/HEAD only), names decide for the rest.
            if (!ToolExecutionRetryPolicy.isIdempotentReadOnly(definition.name, args)) break
            prefix += call to definition
            argsByCallId[call.toolCallId] = args
        }

        if (prefix.size < MIN_PREFIX) return emptyMap()

        val permits = Semaphore(MAX_IN_FLIGHT)
        return coroutineScope {
            prefix.map { (call, definition) ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        val remaining = remainingBudgetMs()
                        if (remaining <= 0L) {
                            null
                        } else {
                            val output = try {
                                withTimeoutOrNull(remaining) {
                                    definition.execute(argsByCallId.getValue(call.toolCallId))
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Throwable) {
                                // Not cached: the sequential path retries the call and builds
                                // the error envelope the model is used to seeing.
                                null
                            }
                            output?.let { call.toolCallId to it }
                        }
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }
}
