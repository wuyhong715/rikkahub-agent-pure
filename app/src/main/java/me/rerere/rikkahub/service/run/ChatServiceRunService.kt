package me.rerere.rikkahub.service.run

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunStatus
import me.rerere.rikkahub.data.agentrun.RunCheckpointRepository
import me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.runtime.util.JsonInstant
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.awaitGenerationTerminal
import me.rerere.rikkahub.service.mission.MissionSessions
import kotlin.uuid.Uuid

private const val TAG = "ChatServiceRunService"

/**
 * P5 — the prompt a resumed run is re-entered with. It is appended as a visible user turn
 * (honest transcript: "this run was resumed"), and it tells the model to verify anything
 * that was mid-execution instead of blindly replaying it.
 */
private const val RESUME_INSTRUCTION =
    "[Resume] The previous run was interrupted when the app process was killed. " +
        "Continue the task from where it stopped. Do not repeat work that is already " +
        "finished; verify the state of anything that was mid-execution before retrying it."

/**
 * P3/P5 — the first [RunService] implementation.
 *
 * As of P5 the **ledger row is authoritative**: [submit] opens the `agent_runs` row and
 * uses its id as the run id (surface-facing handle, journal `run_id`, checkpoint key), so
 * there is exactly one identity per run instead of a ledger id and a handle id that had
 * to be correlated after the fact.
 *
 * Behaviour contract (unchanged for surfaces):
 *  * [submit] creates a fresh conversation, persists it, wires it into ChatService, marks
 *    it headless (unless [RunRequest.headless] is false), opens the ledger row, binds the
 *    conversation to that run (so the loop's per-step checkpoints land on the right row),
 *    emits `RUN_STARTED`, and kicks off generation.
 *  * [awaitTerminal] blocks until the generation job terminates or the timeout elapses.
 *    On timeout it stops the generation. Whatever the exit path, the ledger row is marked
 *    terminal, the conversation binding is released, `RUN_FINISHED` is journaled and the
 *    checkpoint is cleared when the run succeeded (a failed run keeps its breakpoint).
 *  * [cancel] stops generation, journals the cancellation and marks the ledger cancelled.
 *  * [resume] re-enters generation on the run's own conversation from its checkpoint —
 *    used by boot recovery for interrupted autonomous runs.
 *  * [events] returns the conversation-scoped journal stream from the P1 journal.
 *
 * **Error containment**: every non-essential operation (headless mark, journal emit,
 * ledger/checkpoint writes, generation kick-off) is wrapped — a failure here must not
 * break the surface's run. [awaitTerminal]'s [RunStatus.FAILED] envelope is the designed
 * failure path.
 */
class ChatServiceRunService(
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val runEventRepository: RunEventRepository,
    private val agentRunRepository: AgentRunRepository,
    private val checkpointRepository: RunCheckpointRepository,
) : RunService {

    /**
     * Create the conversation, wire it into ChatService, mark it headless, open the
     * authoritative ledger row, journal the start, and kick off generation.
     */
    override suspend fun submit(request: RunRequest): RunHandle {
        val effectiveTitle = request.title ?: "[Run] ${request.prompt.take(24)}"
        // 批 A — a mission round may ask to reuse its execution conversation; anything else (or a
        // stale id that no longer resolves) gets a fresh conversation, exactly as before.
        val existingConversation = request.conversationId
            ?.let { id -> runCatching { conversationRepo.getConversationById(id) }.getOrNull() }
        val conv = existingConversation ?: Conversation.ofId(
            id = Uuid.random(),
            assistantId = request.assistantId,
            newConversation = true,
        ).copy(title = effectiveTitle, chatModelId = request.modelId)

        // Persist + wire the conversation into ChatService's generation machinery.
        if (existingConversation == null) {
            conversationRepo.insertConversation(conv)
        }
        chatService.initializeConversation(conv.id)

        // Mark headless so tool-call approvals are auto-granted — there is no human to ask.
        if (request.headless) {
            runCatching { HeadlessConversations.mark(conv.id) }
        }

        // P6 — an expert's prompt rides in the SYSTEM slot (the same mechanism SubAgentEngine
        // uses) rather than being prepended to the prompt, so the archived transcript keeps a
        // clean first user turn. Released with the headless mark on every terminal path below.
        request.systemPrompt?.takeIf { it.isNotBlank() }
            ?.let { runCatching { ConversationSystemAddendum.set(conv.id, it) } }

        // P5 — the ledger row IS the run. Its id doubles as the handle and the journal /
        // checkpoint key, which is what lets boot recovery find this run's breakpoint.
        val runId = agentRunRepository.open(
            kind = request.kind,
            domainId = request.domainId ?: "run:${conv.id}",
            parentRunId = request.parentRunId,
            metadata = buildJsonObject {
                put("assistant_id", request.assistantId.toString())
                request.missionId?.let { put("mission_id", it) }
            },
        )
        ConversationRunRegistry.bind(conv.id, runId, request.kind.wire)

        val handle = RunHandle(
            id = runId,
            conversationId = conv.id,
            timeoutMs = request.timeoutMs,
            missionId = request.missionId,
        )

        // 批 A — bind the mission to the conversation BEFORE generation is dispatched.
        // ChatService's tool assembly reads this registry synchronously to decide whether the
        // round gets `mission_report`; registering after `submit` returned (as the launcher used
        // to) raced the first model request, so a round could start with no way to report
        // done/blocked — and a mission that can't report never ends itself.
        //
        // Only a Mission *round* owns the signal tool: `mission_dispatch` children carry the
        // parent missionId for journaling, but they are SubAgent runs and must not be able to
        // declare the parent mission done.
        if (request.kind == AgentRunKind.Mission) {
            request.missionId?.let { missionId ->
                runCatching { MissionSessions.register(conv.id.toString(), missionId) }
            }
        }

        runCatching {
            runEventRepository.emit(
                type = RunEventType.RUN_STARTED,
                title = effectiveTitle,
                payloadJson = JsonInstant.encodeToString(
                    buildJsonObject {
                        put("runId", runId)
                        put("assistantId", request.assistantId.toString())
                        put("kind", request.kind.wire)
                    }
                ),
                missionId = request.missionId,
                conversationId = conv.id.toString(),
                runId = runId,
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
     * Block until the generation job terminates or [RunHandle.timeoutMs] elapses, then
     * settle the ledger, the journal and the checkpoint for this run.
     */
    override suspend fun awaitTerminal(handle: RunHandle): RunResult =
        awaitSettled(handle, throughApprovals = false)

    /**
     * [awaitTerminal] for rounds a human is steering (SEMI / MANUAL missions).
     *
     * Those rounds go idle twice: once when generation stops on a tool call that needs the
     * user's answer, and once when it really ends. Only the second one ends the round - the
     * answer restarts generation on the very same conversation. Settling at the first idle closed
     * the ledger and journaled RUN_FINISHED under work that was still alive, so the supervisor
     * scored a half-done round, and the round's real ending was never settled at all: the mission
     * stayed in RUNNING forever.
     */
    override suspend fun awaitInteractiveTerminal(handle: RunHandle): RunResult =
        awaitSettled(handle, throughApprovals = true)

    private suspend fun awaitSettled(handle: RunHandle, throughApprovals: Boolean): RunResult {
        // Default in case neither the try nor catch assigns (shouldn't happen, but the
        // compiler needs a definite assignment for the finally block's journal emit).
        var result = RunResult(
            RunStatus.FAILED,
            handle.conversationId,
            "awaitTerminal did not reach a terminal state",
        )
        // Parked = the round stopped on a tool approval nobody has answered yet. It is NOT over,
        // so the settlement below is skipped entirely: releasing the headless mark, the system
        // addendum and the registry binding here would strip the run out from under a generation
        // that is about to continue, and RUN_FINISHED would tell the supervisor the round ended.
        var parked = false
        try {
            val completed = watchRound(handle, throughApprovals)
            if (completed == null) {
                parked = true
                Log.i(TAG, "awaitTerminal: run ${handle.id} parked on a tool approval; leaving it open")
                return RunResult(
                    RunStatus.PARKED,
                    handle.conversationId,
                    "waiting for tool approval",
                )
            }
            // P6 — snapshot the child's own answer while its conversation is still around.
            // Taken for BOTH terminal paths on purpose: a timed-out run has usually written most
            // of its work already ("timeout != no work"), and that partial output is exactly what
            // an orchestrating caller needs in order to decide what to salvage.
            val finalText = lastAssistantText(handle.conversationId)
            result = if (completed) {
                RunResult(RunStatus.COMPLETED, handle.conversationId, finalText = finalText)
            } else {
                // Timeout — stop the runaway generation before reporting.
                runCatching { chatService.stopGeneration(handle.conversationId) }
                    .onFailure { runCatching { Log.w(TAG, "awaitTerminal: stopGeneration failed after timeout for run ${handle.id}", it) } }
                RunResult(
                    RunStatus.TIMED_OUT,
                    handle.conversationId,
                    "run exceeded ${handle.timeoutMs}ms",
                    finalText = finalText,
                )
            }
        } catch (t: Throwable) {
            result = RunResult(RunStatus.FAILED, handle.conversationId, "${t::class.simpleName}: ${t.message}")
        } finally {
            if (!parked) settleTerminal(handle, result)
        }
        return result
    }

    /**
     * Wait for the round to end.
     *
     * Returns true when generation finished on its own, false when the timeout expired, and null
     * when the round is parked on an approval the human has not answered yet (only reachable with
     * [throughApprovals]).
     */
    private suspend fun watchRound(handle: RunHandle, throughApprovals: Boolean): Boolean? {
        val flow = chatService.getGenerationJobStateFlow(handle.conversationId)
        if (!throughApprovals) {
            return awaitGenerationTerminal(flow = flow, timeoutMs = handle.timeoutMs)
        }
        val deadline = System.currentTimeMillis() + handle.timeoutMs
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
            val completed = awaitGenerationTerminal(flow = flow, timeoutMs = remaining)
            if (!completed) return false
            if (!chatService.hasPendingToolApprovals(handle.conversationId)) return true
            // Parked on a human decision: wait for the answer to restart generation instead of
            // polling, and instead of faking an ending. The wait shares the run's deadline so an
            // abandoned approval cannot pin this coroutine forever - when it expires the run is
            // left open (never settled under a live round) and the supervisor's own deadline
            // logic owns the mission from there.
            val resumed = withTimeoutOrNull(remaining) {
                flow.first { it != null && it.isActive }
            } != null
            if (!resumed) return null
        }
    }

    /** Post-run cleanup: release the surface marks, settle the ledger, journal the outcome. */
    private suspend fun settleTerminal(handle: RunHandle, result: RunResult) {
        runCatching { HeadlessConversations.unmark(handle.conversationId) }
        runCatching { ConversationSystemAddendum.clear(handle.conversationId) }
        ConversationRunRegistry.unbind(handle.conversationId)
        runCatching { settleLedger(handle, result) }
        runCatching {
            runEventRepository.emit(
                type = RunEventType.RUN_FINISHED,
                title = handle.id,
                detail = result.status.name,
                payloadJson = result.failure?.let {
                    JsonInstant.encodeToString(buildJsonObject { put("failure", it) })
                },
                missionId = handle.missionId,
                conversationId = handle.conversationId.toString(),
                runId = handle.id,
            )
        }
    }

    /**
     * Abort a run: stop the generation, release the headless mark, settle the ledger and
     * journal the cancellation. Best-effort on every step — cancel must never throw.
     */
    override suspend fun cancel(handle: RunHandle) {
        runCatching { chatService.stopGeneration(handle.conversationId) }
            .onFailure { runCatching { Log.w(TAG, "cancel: stopGeneration failed for run ${handle.id}", it) } }
        runCatching { HeadlessConversations.unmark(handle.conversationId) }
        runCatching { ConversationSystemAddendum.clear(handle.conversationId) }
        ConversationRunRegistry.unbind(handle.conversationId)
        runCatching { agentRunRepository.markTerminal(handle.id, AgentRunStatus.cancelled, "cancelled by surface") }
        runCatching { checkpointRepository.clear(handle.id) }
        runCatching {
            runEventRepository.emit(
                type = RunEventType.RUN_FINISHED,
                title = handle.id,
                detail = RunStatus.CANCELLED.name,
                // Without the mission id this event landed outside the mission's timeline, so a
                // user-initiated cancel was invisible there while every other ending showed up.
                missionId = handle.missionId,
                conversationId = handle.conversationId.toString(),
                runId = handle.id,
            )
        }
    }

    /**
     * P5 — re-enter generation on the run's own conversation from its checkpoint.
     *
     * Preconditions are read from persisted state on purpose: boot recovery calls this in
     * a fresh process where nothing in memory survived. The run's ledger row must still be
     * reopenable (non-terminal after the recovery coordinator decided to resume), the
     * conversation must exist, and a checkpoint must name that conversation.
     */
    override suspend fun resume(runId: String): RunResult? {
        val checkpoint = checkpointRepository.get(runId) ?: return null
        val conversationId = checkpoint.conversationId
            ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
            ?: return null
        conversationRepo.getConversationById(conversationId) ?: return null

        if (!agentRunRepository.reopen(runId)) {
            return null
        }
        checkpointRepository.bumpAttempts(runId)

        chatService.initializeConversation(conversationId)
        runCatching { HeadlessConversations.mark(conversationId) }
        ConversationRunRegistry.bind(conversationId, runId, checkpoint.kind)
        // 批 A — a boot-resumed mission round bypasses MissionLauncher entirely (P5 recovery
        // calls this method directly), so the mission↔conversation binding has to be restored
        // here too. Without it the resumed round has no `mission_report` tool and can only end
        // by timing out. Children of a mission carry the same missionId but are SubAgent runs;
        // they do not own the signal tool.
        if (checkpoint.kind == AgentRunKind.Mission.wire) {
            checkpoint.missionId?.let { missionId ->
                runCatching { MissionSessions.register(conversationId.toString(), missionId) }
            }
        }

        runCatching {
            runEventRepository.emit(
                type = RunEventType.RUN_STARTED,
                title = "(resumed) ${checkpoint.kind}",
                detail = "resumed from step ${checkpoint.stepIndex}",
                payloadJson = JsonInstant.encodeToString(
                    buildJsonObject {
                        put("runId", runId)
                        put("resumed", JsonPrimitive(true))
                        put("stepIndex", JsonPrimitive(checkpoint.stepIndex))
                        put("phase", JsonPrimitive(checkpoint.phase))
                    }
                ),
                missionId = checkpoint.missionId,
                conversationId = conversationId.toString(),
                runId = runId,
            )
        }

        val handle = RunHandle(
            id = runId,
            conversationId = conversationId,
            timeoutMs = RunRequest.DEFAULT_TIMEOUT_MS,
            missionId = checkpoint.missionId,
        )
        runCatching {
            chatService.sendMessage(
                conversationId = conversationId,
                content = listOf(UIMessagePart.Text(RESUME_INSTRUCTION)),
            )
        }.onFailure {
            runCatching { Log.w(TAG, "resume: sendMessage failed for run $runId", it) }
            runCatching { agentRunRepository.markTerminal(runId, AgentRunStatus.failed, "resume dispatch failed") }
            ConversationRunRegistry.unbind(conversationId)
            return null
        }
        return awaitTerminal(handle)
    }

    /**
     * The run's journal stream (conversation-scoped), sourced from the P1 journal. Returns
     * the live flow so a surface observing it sees events as they are written.
     */
    override fun events(handle: RunHandle): Flow<List<RunEventEntity>> =
        runEventRepository.observeByConversation(handle.conversationId.toString())

    /** Ledger + checkpoint settlement shared by the terminal paths. */
    private suspend fun settleLedger(handle: RunHandle, result: RunResult) {
        val status = when (result.status) {
            RunStatus.COMPLETED -> AgentRunStatus.succeeded
            RunStatus.CANCELLED -> AgentRunStatus.cancelled
            RunStatus.TIMED_OUT, RunStatus.FAILED -> AgentRunStatus.failed
            // Unreachable: a parked round is deliberately not settled (see awaitSettled). Mapped
            // to the ledger's own parked status so the pairing stays meaningful if that changes.
            RunStatus.PARKED -> AgentRunStatus.awaiting_approval
        }
        agentRunRepository.markTerminal(handle.id, status, result.failure)
        // A successful run has nothing left to resume; a failed one keeps its breakpoint so
        // the user can still see where it stopped.
        if (status == AgentRunStatus.succeeded) {
            checkpointRepository.clear(handle.id)
        }
    }

    /**
     * P6 — the run's last assistant text, or null when the conversation is gone or the model
     * never produced one.
     *
     * Best-effort by construction: this copy exists for the orchestrating caller, so a read
     * failure must never turn a finished run into a failed one.
     */
    private suspend fun lastAssistantText(conversationId: Uuid): String? = runCatching {
        conversationRepo.getConversationById(conversationId)
            ?.currentMessages
            ?.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.toText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
