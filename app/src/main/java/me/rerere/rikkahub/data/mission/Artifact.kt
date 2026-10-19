package me.rerere.rikkahub.data.mission

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.runtime.util.JsonInstant

/**
 * A file artifact produced by a tool execution during a mission run.
 *
 * Projected from [RunEventType.ARTIFACT] events in the run-event journal — **not a
 * separate table**. The projection lives in [RunEventEntity.toArtifact]. This keeps the
 * artifact surface first-class (openable / shareable / reviewable) without a schema
 * migration: the ARTIFACT event already carries the path; batch 1 enriches its payload
 * with change metadata (see [ArtifactChange]).
 *
 * @see ArtifactChange for how the diff summary is computed at tool-execution time.
 */
data class Artifact(
    /** Stable id — the source ARTIFACT event's id. */
    val id: String,
    val missionId: String?,
    /** File path inside the workspace rootfs. Source: ARTIFACT event `title`. */
    val path: String,
    /** Tool that produced it (write_text_file / workspace_write_file / workspace_edit_file). */
    val sourceTool: String,
    /** "created" for new files, "modified" for edits. Null for legacy rows without change metadata. */
    val changeType: String?,
    val linesAdded: Int,
    val linesRemoved: Int,
    /** Truncated excerpt (first ~300 chars) of the new content (modified) or null (created). */
    val diffExcerpt: String?,
    /** Optional human-readable summary (ARTIFACT event `detail`). */
    val summary: String?,
    val runId: String?,
    val createdAt: Long,
)

/**
 * Change metadata computed at tool-execution time by
 * [MissionEngine.onToolExecuted] for [ARTIFACT_TOOLS] and embedded in the ARTIFACT
 * event's payload, so the workbench can render a review summary without re-reading files.
 *
 * - `workspace_write_file` / `write_text_file` → changeType = "created",
 *   linesAdded = line count of the `text` arg.
 * - `workspace_edit_file` → changeType = "modified", linesAdded/linesRemoved derived
 *   from `old_text` / `new_text` line counts, diffExcerpt = first 300 chars of new_text.
 */
data class ArtifactChange(
    val changeType: String,
    val linesAdded: Int,
    val linesRemoved: Int,
    val diffExcerpt: String?,
)

/**
 * Aggregated settlement for a mission — the data behind the settlement card on the
 * mission detail page. Pure projection from [MissionEntity] + run_events +
 * usage_records. **Not persisted**; computed on demand by the ViewModel from flows.
 */
data class MissionSettlement(
    val mission: MissionEntity,
    val toolCallCount: Int,
    val artifactCount: Int,
    val artifacts: List<Artifact>,
    val totalTokens: Long,
    val totalCostUsd: Double,
    val activeDurationMs: Long,
    val acceptanceTotal: Int,
    val acceptanceChecked: Int,
    val lastActivityAt: Long?,
) {
    val acceptanceProgress: Float
        get() = if (acceptanceTotal == 0) 0f else acceptanceChecked.toFloat() / acceptanceTotal

    /** True when every acceptance criterion is checked — the gate for REVIEW → DONE. */
    val canComplete: Boolean
        get() = acceptanceTotal > 0 && acceptanceChecked >= acceptanceTotal
}

// ---------------------------------------------------------------------------
// Projection — ARTIFACT event → Artifact.
// ---------------------------------------------------------------------------

/**
 * Project an ARTIFACT event into an [Artifact]. Returns null for non-ARTIFACT events
 * or malformed payloads.
 */
fun RunEventEntity.toArtifact(): Artifact? {
    val typeEnum = RunEventType.entries.firstOrNull { it.name == type }
    if (typeEnum != RunEventType.ARTIFACT) return null
    val payload = payloadJson?.let {
        runCatching { JsonInstant.parseToJsonElement(it).jsonObject }.getOrNull()
    }
    return Artifact(
        id = id,
        missionId = missionId,
        path = title,
        sourceTool = payload?.get("sourceTool")?.jsonPrimitive?.contentOrNull ?: "unknown",
        changeType = payload?.get("changeType")?.jsonPrimitive?.contentOrNull,
        linesAdded = payload?.get("linesAdded")?.jsonPrimitive?.intOrNull ?: 0,
        linesRemoved = payload?.get("linesRemoved")?.jsonPrimitive?.intOrNull ?: 0,
        diffExcerpt = payload?.get("diffExcerpt")?.jsonPrimitive?.contentOrNull,
        summary = detail,
        runId = runId,
        createdAt = createdAt,
    )
}
