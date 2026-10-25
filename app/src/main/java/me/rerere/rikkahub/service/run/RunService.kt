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

    /**
     * [awaitTerminal] for rounds a human is steering (SEMI / MANUAL missions).
     *
     * Such a round goes idle *twice*: once when generation stops on a tool call that needs the
     * user's answer, and once when it really ends. Only the second idle is the end — the answer
     * restarts generation on the same conversation. Implementations that cannot park keep the
     * plain [awaitTerminal] behaviour by default.
     */
    suspend fun awaitInteractiveTerminal(handle: RunHandle): RunResult = awaitTerminal(handle)

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
    /**
     * 批 A — reuse an existing conversation instead of creating a fresh one. Mission rounds run
     * their whole life inside a single execution conversation, so every round after the first
     * (and every system continuation) targets the mission's conversation. When null the run
     * creates a new conversation exactly as before; an id that no longer resolves also falls
     * back to a fresh conversation (callers needing strict existence checks do them first).
     */
    val conversationId: Uuid? = null,
    /** Which autonomous path is submitting (ledger discriminator, P5 Run-as-authority). */
    val kind: AgentRunKind = AgentRunKind.Cron,
    /** Per-kind domain id for the ledger; defaults to `run:<conversationId>`. */
    val domainId: String? = null,
    /** Set when this run was dispatched by another run (P6 orchestration). */
    val parentRunId: String? = null,
    /** Optional per-run step cap for the loop; null keeps the global default. */
    val maxSteps: Int? = null,
    /**
     * P6 — an expert's system prompt for this run, appended to the assistant's own system
     * prompt via [me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum] — the same
     * mechanism SubAgentEngine uses, so the transcript keeps a clean first user turn and the
     * vector index never embeds the scaffolding. Null = no extra layer.
     */
    val systemPrompt: String? = null,
    /**
     * P6 — an expert's chat model for this run. Null = inherit the assistant's / settings'
     * model, exactly as before.
     */
    val modelId: Uuid? = null,
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
    /** 批 A — mission coordinate, echoed into the RUN_STARTED / RUN_FINISHED journal entries. */
    val missionId: String? = null,
)

enum class RunStatus { COMPLETED, TIMED_OUT, FAILED, CANCELLED, PARKED }

/** Terminal outcome of a run. */
data class RunResult(
    val status: RunStatus,
    val conversationId: Uuid,
    val failure: String? = null,
    /**
     * P6 — the run's final assistant text, when the implementation can produce it without
     * extra work.
     *
     * The conversation remains the authoritative copy (readers still fetch it via
     * ConversationRepository, exactly as the pre-P3 cron path did); this field exists so an
     * orchestrating caller — `mission_dispatch` aggregating a fan-out — can hand each child's
     * answer straight back to the model instead of making it go and search N conversations
     * for something the dispatcher already had in hand. Default null so every existing
     * implementation and test keeps compiling and behaving as before.
     */
    val finalText: String? = null,
)
