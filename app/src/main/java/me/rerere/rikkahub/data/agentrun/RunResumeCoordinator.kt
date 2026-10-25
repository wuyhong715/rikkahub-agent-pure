package me.rerere.rikkahub.data.agentrun

import android.util.Log
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.service.run.RunService
import me.rerere.rikkahub.service.run.RunStatus
import kotlin.uuid.Uuid

private const val TAG = "RunResumeCoordinator"

/**
 * P5 — turns a stranded ledger row plus its checkpoint into either a resumed run or an
 * explicit breakpoint.
 *
 * Called once per stranded row from [AgentRunBootRecovery], in the boot sequence, before
 * the row is flipped to `process_lost`. The decision itself is [RunResumePolicy]'s (pure,
 * unit-tested); this class only supplies the facts and performs the action.
 *
 * Every path is journaled: a resumed run gets a `NOTE` saying where it resumed from, and a
 * handed-off run gets the human-readable breakpoint (`step 7, tool calls: [...]`) so the
 * workbench timeline explains the interruption instead of silently showing a dead row.
 */
class RunResumeCoordinator(
    private val agentRunRepository: AgentRunRepository,
    private val checkpointRepository: RunCheckpointRepository,
    private val conversationRepository: ConversationRepository,
    private val runService: RunService,
    private val runEventRepository: RunEventRepository,
    /** Mission ownership lookup — boot recovery must not resurrect a finished mission. */
    private val missionRepository: MissionRepository,
) {

    /**
     * Try to resume [run]. Returns true only when the resumed run reached COMPLETED —
     * anything else means boot recovery should treat the row as lost (or, if the resume
     * itself died, leave it stranded for the next boot).
     */
    suspend fun tryResume(run: AgentRun): Boolean {
        val checkpoint = runCatching { checkpointRepository.get(run.id) }.getOrNull()
            ?: return false
        val conversationId = checkpoint.conversationId
            ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
            ?: return false
        val conversationExists = runCatching {
            conversationRepository.getConversationById(conversationId) != null
        }.getOrDefault(false)

        val decision = RunResumePolicy.decide(
            kind = run.kind,
            checkpoint = checkpoint,
            conversationExists = conversationExists,
            runId = run.id,
            mission = missionClaimFor(kind = run.kind, missionId = checkpoint.missionId),
        )

        return when (decision) {
            RunResumeDecision.AUTO_RESUME -> {
                journal(
                    run = run,
                    conversationId = conversationId,
                    title = "resuming",
                    detail = "auto-resume from step ${checkpoint.stepIndex}",
                    missionId = checkpoint.missionId,
                )
                val result = runCatching { runService.resume(run.id) }.getOrNull()
                val completed = result?.status == RunStatus.COMPLETED
                if (!completed) {
                    journal(
                        run = run,
                        conversationId = conversationId,
                        title = "resume failed",
                        detail = result?.failure ?: "resume attempt did not complete",
                        missionId = checkpoint.missionId,
                    )
                }
                completed
            }

            RunResumeDecision.HAND_OFF -> {
                journal(
                    run = run,
                    conversationId = conversationId,
                    title = "interrupted",
                    detail = RunResumePolicy.describeBreakpoint(checkpoint),
                    missionId = checkpoint.missionId,
                )
                false
            }

            RunResumeDecision.NOT_RESUMABLE -> false
        }
    }

    /**
     * The mission's claim on a stranded run, or null when the run is not a mission round /
     * the mission row is gone. Only mission runs are gated: cron runs have no mission.
     */
    private suspend fun missionClaimFor(kind: String, missionId: String?): MissionClaim? {
        if (kind != AgentRunKind.Mission.wire) return null
        val id = missionId ?: return null
        return runCatching {
            missionRepository.getById(id)?.let { mission ->
                MissionClaim(
                    status = mission.status,
                    executionState = mission.executionState,
                    lastRunId = mission.lastRunId,
                )
            }
        }.getOrNull()
    }

    private suspend fun journal(
        run: AgentRun,
        conversationId: Uuid,
        title: String,
        detail: String,
        missionId: String? = null,
    ) {
        runCatching {
            runEventRepository.emit(
                type = RunEventType.NOTE,
                title = title,
                detail = detail,
                // A mission round's recovery has to show up on the mission timeline; hardcoding
                // null put it everywhere except where the user was looking.
                missionId = missionId,
                conversationId = conversationId.toString(),
                runId = run.id,
            )
        }.onFailure { runCatching { Log.w(TAG, "journal(${run.id}) failed", it) } }
        runCatching { agentRunRepository.setStatus(run.id, AgentRunStatus.running) }
    }
}
