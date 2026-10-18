package me.rerere.rikkahub.data.runevent

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.mission.MissionEntity

/**
 * Run Event Journal — the unified, append-only event stream for agent execution (P1 of
 * the harness re-architecture).
 *
 * This table **generalises the day-one `mission_events` timeline** into a journal that
 * any execution path can write to: chat turns, sub-agent dispatches, cron jobs,
 * workflows and (in later phases) authoritative `Run`s. An event is scoped by up to
 * three orthogonal coordinates, all nullable so partial-scope events are legal:
 *
 * - [missionId]   — the workbench mission the event belongs to (timeline source).
 * - [conversationId] — the chat conversation that produced it (debugging / future views).
 * - [runId]       — the execution run id when known (sub-agent run id today;
 *                   `agent_runs.id` once P3 wires the autonomous paths through).
 *
 * The journal is **observability infrastructure**: writers are best-effort and must
 * never break the execution they describe (see [RunEventRepository.emit]).
 *
 * Consumers today: the workbench mission timeline ([observeByMission]). Consumers in
 * later phases: cost accounting (P2), crash recovery cursors (P5), run inspection (P6).
 */
enum class RunEventType {
    // --- mission lifecycle (carried over from MissionEventType) ---
    CREATED,
    STATUS_CHANGED,
    NOTE,
    ARTIFACT,
    MILESTONE,
    SUBAGENT_DISPATCHED,
    SUBAGENT_FINISHED,
    ACCEPTANCE_UPDATED,

    // --- execution journal (new in P1) ---
    /** One row per tool execution: title = tool name, detail = output excerpt,
     *  payload = {"tool", "args" excerpt}. Emitted for every tool call in the
     *  generation loop, regardless of mission binding. */
    TOOL_RESULT,
}

/**
 * A single journal entry. Rows are append-only; retention is enforced by scope
 * ([RunEventDao.trimByMission] / [RunEventDao.trimByConversation]).
 *
 * Events carry a foreign key to [MissionEntity] with CASCADE delete so removing a
 * mission also removes its timeline. `conversation_id` / `run_id` are deliberately
 * **not** foreign keys: the journal must survive whatever it describes.
 */
@Entity(
    tableName = "run_events",
    indices = [
        Index("mission_id", "created_at"),
        Index("conversation_id", "created_at"),
        Index("run_id", "created_at"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = MissionEntity::class,
            parentColumns = ["id"],
            childColumns = ["mission_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RunEventEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("mission_id")
    val missionId: String? = null,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("run_id")
    val runId: String? = null,
    @ColumnInfo("type")
    val type: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("detail")
    val detail: String? = null,
    @ColumnInfo("payload_json")
    val payloadJson: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
