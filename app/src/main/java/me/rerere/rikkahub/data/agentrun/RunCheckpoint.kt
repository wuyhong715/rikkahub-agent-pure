package me.rerere.rikkahub.data.agentrun

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * P5 — the durable breakpoint of an in-flight Run.
 *
 * One row per run, overwritten at every generation step by the loop's `onCheckpoint`
 * hook. It is deliberately **metadata-only**: the transcript itself lives in the
 * conversation tables, so a checkpoint only has to say *where* the run was, not replay
 * message content. That keeps the row a few hundred bytes no matter how long the run.
 *
 * Recovery reads it on boot ([RunResumePolicy]): a cron run with a checkpoint can be
 * resumed on the same conversation; every other case is reported to the user as an
 * explicit breakpoint instead of silently flipping to `process_lost`.
 *
 * `run_id` is the ledger id (`agent_runs.id`) for autonomous runs, or the synthetic
 * `chat:<conversationId>` for ordinary chat turns — chat turns get breakpoints too, so
 * a killed reply leaves a record, but they are never auto-resumed.
 */
@Entity(
    tableName = "run_checkpoints",
    indices = [
        Index("conversation_id"),
        Index("updated_at_ms"),
    ],
)
data class RunCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "run_id")
    val runId: String,

    /** Conversation the run was writing to, when it has one. */
    @ColumnInfo(name = "conversation_id")
    val conversationId: String? = null,

    /** Mission the run belongs to, when the conversation is bound to one. */
    @ColumnInfo(name = "mission_id")
    val missionId: String? = null,

    /** [AgentRunKind.wire], or `chat` for an interactive turn. */
    @ColumnInfo(name = "kind")
    val kind: String,

    /** Loop phase — `GENERATING` or `EXECUTING_TOOL` (see LoopCheckpoint). */
    @ColumnInfo(name = "phase")
    val phase: String,

    /** Generation-loop step the run was on. */
    @ColumnInfo(name = "step_index")
    val stepIndex: Int,

    /** Request-visible transcript size at that step (recovery sanity check). */
    @ColumnInfo(name = "message_count")
    val messageCount: Int,

    /** JSON array of tool-call ids that were approved/ready but not yet executed. */
    @ColumnInfo(name = "pending_tools")
    val pendingTools: String? = null,

    /** How many times recovery already tried to resume this run. */
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

object RunCheckpointDefaults {
    /** Chat checkpoint rows are decoration; keep them a day, then let the pruner go. */
    const val RETENTION_MS: Long = 24L * 60L * 60L * 1_000L

    /** One automatic resume attempt. A second death of the same run means "needs a human". */
    const val MAX_AUTO_RESUME_ATTEMPTS: Int = 1

    /** `pending_tools` cap — a step can't realistically propose more than this. */
    const val PENDING_TOOLS_MAX_BYTES: Int = 2 * 1024
}
