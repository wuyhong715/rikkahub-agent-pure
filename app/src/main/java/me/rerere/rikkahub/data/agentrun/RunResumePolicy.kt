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
 * - **mission** runs (批 A) are the workbench's execution rounds. A round that was mid-run
 *   when the process died carries on from its checkpoint exactly like cron — the mission
 *   exists to run to its goal, so a single death must not end it. A second death hands off
 *   to MissionSupervisor, which re-evaluates the mission as a whole (start a fresh round
 *   with a handoff, or park it with a notification) instead of replaying a broken run.
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

/**
 * The mission row's claim on a stranded mission run — plain strings so this policy stays
 * free of Room / mission types (bare-JVM unit tests).
 *
 * A mission round is only resumable while the mission still considers it the live round:
 * status `ACTIVE`, execution posture `RUNNING`, and [lastRunId] pointing at this run.
 * A mission the user completed / archived / stopped fails that test, and its stale run
 * must be declared lost instead of being replayed into the conversation.
 */
data class MissionClaim(
    val status: String?,
    val executionState: String?,
    val lastRunId: String?,
)

object RunResumePolicy {

    /** Kinds whose execution is a pure conversation turn and therefore replay-safe. */
    val AUTO_RESUMABLE_KINDS: Set<String> = setOf(AgentRunKind.Cron.wire, AgentRunKind.Mission.wire)

    /** Synthetic kind for ordinary chat turns — checkpointed, never auto-resumed. */
    const val CHAT_KIND = "chat"

    /** Status / posture a mission must still be in to own a resumable round. */
    private const val MISSION_ACTIVE = "ACTIVE"
    private const val MISSION_RUNNING = "RUNNING"

    fun decide(
        kind: String,
        checkpoint: RunCheckpointEntity?,
        conversationExists: Boolean,
        attempts: Int = checkpoint?.attempts ?: 0,
        maxAttempts: Int = RunCheckpointDefaults.MAX_AUTO_RESUME_ATTEMPTS,
        /** The ledger id of the run being considered; used to check the mission's claim. */
        runId: String? = null,
        /** Mission ownership fact for [AgentRunKind.Mission] runs; null when unknown. */
        mission: MissionClaim? = null,
    ): RunResumeDecision {
        if (checkpoint == null) return RunResumeDecision.NOT_RESUMABLE
        if (!conversationExists) return RunResumeDecision.NOT_RESUMABLE
        if (kind == CHAT_KIND) return RunResumeDecision.HAND_OFF
        if (kind !in AUTO_RESUMABLE_KINDS) return RunResumeDecision.HAND_OFF
        // A mission round is only replay-safe while the mission still owns it as its live
        // round. A terminal / parked mission (done, archived, stopped, blocked) must never
        // be resurrected by boot recovery — that is what made a completed task continue
        // talking after a restart.
        if (kind == AgentRunKind.Mission.wire && !missionOwnsRun(mission, runId)) {
            return RunResumeDecision.NOT_RESUMABLE
        }
        if (attempts >= maxAttempts) return RunResumeDecision.HAND_OFF
        return RunResumeDecision.AUTO_RESUME
    }

    /** True when [mission] is active, running, and names [runId] as its last round. */
    private fun missionOwnsRun(mission: MissionClaim?, runId: String?): Boolean {
        if (mission == null) return false
        if (runId == null) return false
        if (mission.status != MISSION_ACTIVE) return false
        if (mission.executionState != MISSION_RUNNING) return false
        if (mission.lastRunId != runId) return false
        return true
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
