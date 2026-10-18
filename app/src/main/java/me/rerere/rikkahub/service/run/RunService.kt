package me.rerere.rikkahub.service.run

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.runevent.RunEventEntity
import kotlin.uuid.Uuid

/**
 * RunService — the surface-facing API for executing agent runs (P3 of the harness
 * re-architecture).
 *
 * **Surfaces never touch ChatService.** A surface (cron today; Telegram and others in
 * P4 when the streaming surface lands) speaks only this narrow contract:
 * [submit] a [RunRequest], [awaitTerminal] for the outcome, [cancel] to abort, and
 * [events] to observe the run's journal stream (P1).
 *
 * The P3 implementation ([ChatServiceRunService]) is a facade over ChatService — the
 * generation machinery stays conversation-backed for now. Swapping the implementation
 * for a conversation-free runtime is P4's job; call sites must not notice.
 *
 * Behaviour contract (mirrors the pre-P3 cron path exactly):
 * - [submit] creates a fresh conversation, marks it headless (no approval prompts)
 *  unless [RunRequest.headless] is false, emits RUN_STARTED to the journal, and kicks
 *  off generation before returning.
 * - [awaitTerminal] blocks until the generation job terminates or
 *  [RunRequest.timeoutMs] elapses; on timeout it STOPS the generation (a runaway
 *  would keep burning tokens) and reports [RunStatus.TIMED_OUT]. The headless mark is
 *  released when the run leaves [awaitTerminal]/[cancel], whichever comes first.
 * - [cancel] stops generation, releases the headless mark, and journals the outcome.
 * - Every terminal transition emits RUN_FINISHED with the status in `detail`.
 */
interface RunService {

    suspend fun submit(request: RunRequest): RunHandle

    suspend fun awaitTerminal(handle: RunHandle): RunResult

    suspend fun cancel(handle: RunHandle)

    /**
     * P5 — resume a run from its last checkpoint on the same conversation.
     *
     * Only meaningful for an interrupted run (its ledger row was left non-terminal by a
     * killed process, or [me.rerere.rikkahub.data.agentrun.RunResumePolicy] decided it can
     * be re-entered). Returns null when there is nothing to resume: no checkpoint, the
     * conversation is gone, or the ledger row cannot be reopened.
     */
    suspend fun resume(runId: String): RunResult?

    /** The run's journal stream (conversation-scoped), sourced from the P1 journal. */
    fun events(handle: RunHandle): Flow<List<RunEventEntity>>
}

/** What a surface wants the agent to do. */
data class RunRequest(
    /** Which assistant/agent definition drives the run. */
    val assistantId: Uuid,
    /** The user-visible prompt. */
    val prompt: String,
    /** Conversation title (defaults to a "[Run] …" prefix of the prompt). */
    val title: String? = null,
    /** Optional system-style directive prepended to the prompt (e.g. cron's delivery
     *  directive). Kept as a separate field so the journal records the pure prompt. */
    val promptPrefix: String? = null,
    /** Headless runs auto-approve tool calls (there is nobody to ask). Default true —
     *  surfaces that submit from a UI context and want prompts must pass false. */
    val headless: Boolean = true,
    /** Wall-clock cap for [RunService.awaitTerminal]. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Optional mission coordinate for the journal. */
    val missionId: String? = null,
    /** Which autonomous path is submitting (ledger discriminator, P5 Run-as-authority). */
    val kind: AgentRunKind = AgentRunKind.Cron,
    /** Per-kind domain id for the ledger; defaults to `run:<conversationId>`. */
    val domainId: String? = null,
    /** Set when this run was dispatched by another run (P6 orchestration). */
    val parentRunId: String? = null,
    /** Optional per-run step cap for the loop; null keeps the global default. */
    val maxSteps: Int? = null,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 15L * 60_000L
    }
}

/** A submitted run. [timeoutMs] rides along so [RunService.awaitTerminal] needs no
 *  extra state. */
data class RunHandle(
    val id: String,
    val conversationId: Uuid,
    val timeoutMs: Long,
)

enum class RunStatus { COMPLETED, TIMED_OUT, FAILED, CANCELLED }

/** Terminal outcome of a run. The generated text itself stays in the conversation —
 *  readers fetch it via ConversationRepository (same as the pre-P3 cron path did). */
data class RunResult(
    val status: RunStatus,
    val conversationId: Uuid,
    val failure: String? = null,
)
