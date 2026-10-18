package me.rerere.rikkahub.service.run

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.awaitGenerationTerminal
import me.rerere.rikkahub.runtime.util.JsonInstant
import kotlin.uuid.Uuid

private const val TAG = "ChatServiceRunService"

/**
 * P3 facade — the first [RunService] implementation.
 *
 * This class is a thin adapter that hides ChatService's conversation-backed generation
 * machinery behind the narrow [RunService] contract. Every surface (cron today; Telegram
 * and other streaming surfaces in P4) speaks only [submit] / [awaitTerminal] / [cancel] /
 * [events] and never touches ChatService directly.
 *
 * **Why a facade and not a rewrite?** P3 is the "decouple the surface" cut: the goal is to
 * move every caller onto the new contract *without* changing what actually runs. The
 * generation dance (create conversation → persist → initialize → mark headless → send
 * message → await terminal → unmark) is lifted verbatim from the pre-P3 `CronJobWorker.runLlm`
 * path and wrapped here. Swapping this implementation for a conversation-free runtime is
 * P4's job; call sites must not notice the change.
 *
 * **Behaviour contract** (mirrors the pre-P3 cron path exactly):
 *  * [submit] creates a fresh conversation, persists it, wires it into ChatService, marks
 *    it headless (unless [RunRequest.headless] is false), emits `RUN_STARTED` to the P1
 *    journal, and kicks off generation before returning the [RunHandle].
 *  * [awaitTerminal] blocks until the generation job terminates or the timeout elapses. On
 *    timeout it stops the still-running generation (a runaway would keep burning tokens)
 *    and reports [RunStatus.TIMED_OUT]. Any exception maps to [RunStatus.FAILED]. The
 *    headless mark is released and `RUN_FINISHED` is emitted when the run leaves
 *    [awaitTerminal] or [cancel], whichever comes first.
 *  * [cancel] stops generation, releases the headless mark, and journals the outcome.
 *  * [events] returns the conversation-scoped journal stream from the P1 journal.
 *
 * **Error containment**: as a facade, this class must not leak internal exceptions to the
 * surface — every non-essential operation (headless mark, journal emit, generation kick-off)
 * is wrapped in `runCatching`. The one exception is [awaitTerminal]'s [RunStatus.FAILED]
 * envelope, which IS the designed surface for execution failures.
 */
class ChatServiceRunService(
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val runEventRepository: RunEventRepository,
) : RunService {

    /**
     * Create the conversation, wire it into ChatService, mark it headless, journal the
     * start, and kick off generation.
     *
     * The prompt actually sent to the model is `[promptPrefix]\n\n[prompt]` (matching the
     * pre-P3 cron path, which prepended the delivery directive). Keeping [promptPrefix]
     * separate from [RunRequest.prompt] means the journal's `RUN_STARTED` title records the
     * pure prompt, not the system directive.
     *
     * [sendMessage] is non-suspend — it launches a coroutine internally and returns
     * immediately. If it throws synchronously (rare), the handle is still returned so
     * [awaitTerminal] can report the outcome via the generation job state flow.
     */
    override suspend fun submit(request: RunRequest): RunHandle {
        val effectiveTitle = request.title ?: "[Run] ${request.prompt.take(24)}"
        val conv = Conversation.ofId(
            id = Uuid.random(),
            assistantId = request.assistantId,
            newConversation = true,
        ).copy(title = effectiveTitle)

        // Persist + wire the conversation into ChatService's generation machinery.
        conversationRepo.insertConversation(conv)
        chatService.initializeConversation(conv.id)

        // Mark headless so tool-call approvals are auto-granted — there is no human to ask.
        if (request.headless) {
            runCatching { HeadlessConversations.mark(conv.id) }
        }

        val handle = RunHandle(
            id = Uuid.random().toString(),
            conversationId = conv.id,
            timeoutMs = request.timeoutMs,
        )

        // Journal the start — best-effort (emit is already best-effort internally, but
        // wrapping guards against a misconfigured repository). All three scope coordinates
        // (mission / conversation / run) are filled so the journal can slice the run any way.
        runCatching {
            runEventRepository.emit(
                type = RunEventType.RUN_STARTED,
                title = effectiveTitle,
                payloadJson = JsonInstant.encodeToString(
                    buildJsonObject {
                        put("runId", handle.id)
                        put("assistantId", request.assistantId.toString())
                    }
                ),
                missionId = request.missionId,
                conversationId = conv.id.toString(),
                runId = handle.id,
            )
        }

        // Kick off generation. sendMessage enqueues the message and dispatches; it does not
        // block on the model reply. The prompt mirrors the pre-P3 cron path: prefix + "\n\n"
        // + prompt (see CRON_DELIVERY_DIRECTIVE for why the prefix exists).
        runCatching {
            chatService.sendMessage(
                conversationId = conv.id,
                content = listOf(
                    UIMessagePart.Text(
                        listOfNotNull(request.promptPrefix, request.prompt)
                            .joinToString("\n\n")
                    )
                ),
            )
        }

        return handle
    }

    /**
     * Block until the generation job terminates or [RunHandle.timeoutMs] elapses.
     *
     * On timeout the still-running generation is stopped (a runaway would keep burning
     * tokens, and a later fire of the same job could start a second parallel generation
     * while the first is still going). Any exception maps to [RunStatus.FAILED] — this is
     * the designed failure envelope, not an exception leak.
     *
     * The headless mark is released and `RUN_FINISHED` is emitted in `finally`, so the
     * cleanup happens regardless of the exit path (completion, timeout, exception, or
     * cancellation re-throw).
     */
    override suspend fun awaitTerminal(handle: RunHandle): RunResult {
        // Default in case neither the try nor catch assigns (shouldn't happen, but the
        // compiler needs a definite assignment for the finally block's journal emit).
        var result = RunResult(
            RunStatus.FAILED,
            handle.conversationId,
            "awaitTerminal did not reach a terminal state",
        )
        try {
            val completed = awaitGenerationTerminal(
                flow = chatService.getGenerationJobStateFlow(handle.conversationId),
                timeoutMs = handle.timeoutMs,
            )
            result = if (completed) {
                RunResult(RunStatus.COMPLETED, handle.conversationId)
            } else {
                // Timeout — stop the runaway generation before reporting.
                runCatching { chatService.stopGeneration(handle.conversationId) }
                    .onFailure { runCatching { Log.w(TAG, "awaitTerminal: stopGeneration failed after timeout for run ${handle.id}", it) } }
                RunResult(RunStatus.TIMED_OUT, handle.conversationId, "run exceeded ${handle.timeoutMs}ms")
            }
        } catch (t: Throwable) {
            result = RunResult(RunStatus.FAILED, handle.conversationId, "${t::class.simpleName}: ${t.message}")
        } finally {
            // Release the headless mark and journal the terminal state, regardless of outcome.
            runCatching { HeadlessConversations.unmark(handle.conversationId) }
            runCatching {
                runEventRepository.emit(
                    type = RunEventType.RUN_FINISHED,
                    title = handle.id,
                    detail = result.status.name,
                    payloadJson = result.failure?.let {
                        JsonInstant.encodeToString(buildJsonObject { put("failure", it) })
                    },
                    conversationId = handle.conversationId.toString(),
                    runId = handle.id,
                )
            }
        }
        return result
    }

    /**
     * Abort a run: stop the generation, release the headless mark, and journal the
     * cancellation. Best-effort on every step — cancel must never throw.
     */
    override suspend fun cancel(handle: RunHandle) {
        runCatching { chatService.stopGeneration(handle.conversationId) }
            .onFailure { runCatching { Log.w(TAG, "cancel: stopGeneration failed for run ${handle.id}", it) } }
        runCatching { HeadlessConversations.unmark(handle.conversationId) }
        runCatching {
            runEventRepository.emit(
                type = RunEventType.RUN_FINISHED,
                title = handle.id,
                detail = RunStatus.CANCELLED.name,
                conversationId = handle.conversationId.toString(),
                runId = handle.id,
            )
        }
    }

    /**
     * The run's journal stream (conversation-scoped), sourced from the P1 journal. Returns
     * the live flow so a surface observing it sees events as they are written.
     */
    override fun events(handle: RunHandle): Flow<List<RunEventEntity>> =
        runEventRepository.observeByConversation(handle.conversationId.toString())
}
