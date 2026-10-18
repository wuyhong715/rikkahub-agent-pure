package me.rerere.rikkahub.data.agentrun

/**
 * P5 — what boot recovery should do with a stranded run, as a pure function.
 *
 * The phone-harness reality: the process can be killed at any point, and the right
 * answer differs by run kind.
 *
 * - **cron** runs are unattended and reproducible: if the conversation still exists and
 *   the run has only died once, resume it automatically. Twice means something is wrong
 *   with the environment (battery, permissions), so stop and tell the user.
 * - **telegram / sub-agent / workflow / external-automation** runs have side effects the
 *   recovery layer cannot safely replay (a chat message that may already have been
 *   delivered, a tool that may have half-run): report the breakpoint and let the user
 *   decide.
 * - **chat** turns are never resumed — the user is right there and can simply send again.
 *
 * Kept separate from [RunResumeCoordinator] so the whole decision matrix is unit-testable
 * without Room, Koin or Android.
 */
enum class RunResumeDecision {
    /** Safe to re-enter generation on the same conversation automatically. */
    AUTO_RESUME,

    /** Keep the run interrupted and surface the breakpoint to the user. */
    HAND_OFF,

    /** Nothing to resume (no checkpoint, conversation gone) — plain process_lost. */
    NOT_RESUMABLE,
}

object RunResumePolicy {

    /** Kinds whose execution is a pure conversation turn and therefore replay-safe. */
    val AUTO_RESUMABLE_KINDS: Set<String> = setOf(AgentRunKind.Cron.wire)

    /** Synthetic kind for ordinary chat turns — checkpointed, never auto-resumed. */
    const val CHAT_KIND = "chat"

    fun decide(
        kind: String,
        checkpoint: RunCheckpointEntity?,
        conversationExists: Boolean,
        attempts: Int = checkpoint?.attempts ?: 0,
        maxAttempts: Int = RunCheckpointDefaults.MAX_AUTO_RESUME_ATTEMPTS,
    ): RunResumeDecision {
        if (checkpoint == null) return RunResumeDecision.NOT_RESUMABLE
        if (!conversationExists) return RunResumeDecision.NOT_RESUMABLE
        if (kind == CHAT_KIND) return RunResumeDecision.HAND_OFF
        if (kind !in AUTO_RESUMABLE_KINDS) return RunResumeDecision.HAND_OFF
        if (attempts >= maxAttempts) return RunResumeDecision.HAND_OFF
        return RunResumeDecision.AUTO_RESUME
    }

    /** Human-readable breakpoint line for the recovery notification / journal. */
    fun describeBreakpoint(checkpoint: RunCheckpointEntity): String {
        val tools = checkpoint.pendingTools
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.takeIf { it.isNotBlank() }
            ?: return "interrupted at step ${checkpoint.stepIndex} (${checkpoint.phase.lowercase()})"
        return "interrupted at step ${checkpoint.stepIndex}; unexecuted tool calls: $tools"
    }
}
