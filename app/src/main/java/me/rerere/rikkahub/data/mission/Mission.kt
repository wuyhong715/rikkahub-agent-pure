package me.rerere.rikkahub.data.mission

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.runtime.util.JsonInstant

/**
 * Workbench Mission — lifecycle status for a mission row.
 *
 * The state machine allows missions to flow from active work through review to completion
 * (and eventual archival). Terminal states ([DONE], [ARCHIVED]) cannot be left except via
 * the single [DONE] → [ARCHIVED] edge.
 */
enum class MissionStatus(val isTerminal: Boolean) {
    ACTIVE(false),
    BLOCKED(false),
    REVIEW(false),
    DONE(true),
    ARCHIVED(true);

    /**
     * Whether a transition from `this` to [to] is legal under the mission state machine.
     */
    fun canTransitionTo(to: MissionStatus): Boolean = when (this) {
        ACTIVE -> to in setOf(BLOCKED, REVIEW, DONE, ARCHIVED)
        BLOCKED -> to in setOf(ACTIVE, ARCHIVED)
        REVIEW -> to in setOf(ACTIVE, DONE, ARCHIVED)
        DONE -> to == ARCHIVED
        ARCHIVED -> false
    }
}

/**
 * A workbench mission — a tracked unit of work with acceptance criteria, lifecycle
 * status, and a timeline of events (see [me.rerere.rikkahub.data.runevent.RunEventEntity],
 * the unified run-event journal that superseded the day-one `mission_events` table).
 *
 * JSON columns ([acceptanceJson], [acceptanceCheckedJson]) are decoded via the
 * convenience extensions ([acceptanceCriteria], [acceptanceChecked]) using
 * [JsonInstant].
 */
@Entity(
    tableName = "missions",
    indices = [
        Index("status"),
        Index("conversation_id"),
        Index("updated_at"),
    ],
)
data class MissionEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("description")
    val description: String = "",
    @ColumnInfo("status")
    val status: String = "ACTIVE",
    @ColumnInfo("acceptance_json")
    val acceptanceJson: String = "[]",
    @ColumnInfo("acceptance_checked_json")
    val acceptanceCheckedJson: String = "[]",
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("assistant_id")
    val assistantId: String? = null,
    @ColumnInfo("artifact_count")
    val artifactCount: Int = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("completed_at")
    val completedAt: Long? = null,
)

// ---------------------------------------------------------------------------
// Convenience extensions — decode JSON columns via JsonInstant.
// ---------------------------------------------------------------------------

/** Decode the stored status string into a [MissionStatus], defaulting to [MissionStatus.ACTIVE]. */
val MissionEntity.statusEnum: MissionStatus
    get() = MissionStatus.entries.firstOrNull { it.name == status }
        ?: MissionStatus.ACTIVE

/** Decode [MissionEntity.acceptanceJson] into a list of acceptance criteria strings. */
val MissionEntity.acceptanceCriteria: List<String>
    get() = runCatching {
        JsonInstant.decodeFromString<List<String>>(acceptanceJson)
    }.getOrDefault(emptyList())

/** Decode [MissionEntity.acceptanceCheckedJson] into a list of checked criterion indices. */
val MissionEntity.acceptanceChecked: List<Int>
    get() = runCatching {
        JsonInstant.decodeFromString<List<Int>>(acceptanceCheckedJson)
    }.getOrDefault(emptyList())
