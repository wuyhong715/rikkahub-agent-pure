package me.rerere.rikkahub.data.mission

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.data.usage.UsageLedgerDefaults
import me.rerere.rikkahub.data.usage.UsageRecordDao

/**
 * Read-side projection for the batch-1 delivery loop: mission artifacts and the mission
 * settlement card.
 *
 * Both surfaces are **pure projections** — nothing is persisted here. Artifacts come from
 * the `run_events` journal via [RunEventEntity.toArtifact] (see [Artifact] for why there
 * is no artifacts table), and the settlement aggregates the mission row with its event
 * journal and the usage ledger for the mission's conversation.
 *
 * This class deliberately holds no cache and no state: the ViewModel applies
 * `stateIn(WhileSubscribed)` on top, so a screen that is not visible costs nothing.
 *
 * @param missionRepository mission row + event journal source.
 * @param usageRecordDao usage ledger reader, for the cost / token totals.
 */
class ArtifactsRepository(
    private val missionRepository: MissionRepository,
    private val usageRecordDao: UsageRecordDao,
) {

    /**
     * Artifacts produced by [missionId], oldest first (the order of the underlying event
     * journal). Non-ARTIFACT and malformed events are dropped by the projection.
     */
    fun observeArtifacts(missionId: String): Flow<List<Artifact>> =
        missionRepository.observeEvents(missionId)
            .map { events -> events.mapNotNull { it.toArtifact() } }

    /**
     * The settlement aggregate for [missionId], recomputed whenever the mission row or its
     * event journal changes.
     *
     * Emissions are suppressed until the mission row exists (a deleted mission simply stops
     * emitting, which lets the ViewModel fall back to its `null` initial value).
     *
     * Cost/token totals are scoped to [MissionEntity.conversationId]; a mission with no
     * conversation (ad-hoc mission, or one started outside a chat) reports zero cost rather
     * than folding in unrelated ledger rows.
     */
    fun observeSettlement(missionId: String): Flow<MissionSettlement> =
        combine(
            missionRepository.observeMission(missionId),
            missionRepository.observeEvents(missionId),
        ) { mission, events -> mission to events }
            .map { (mission, events) ->
                if (mission == null) return@map null
                val artifacts = events.mapNotNull { it.toArtifact() }
                val records = mission.conversationId
                    ?.let { usageRecordDao.forConversation(it, UsageLedgerDefaults.QUERY_LIMIT) }
                    .orEmpty()
                MissionSettlement(
                    mission = mission,
                    toolCallCount = events.count { it.type == RunEventType.TOOL_RESULT.name },
                    artifactCount = artifacts.size,
                    artifacts = artifacts,
                    totalTokens = records.sumOf { (it.inputTokens + it.outputTokens).toLong() },
                    totalCostUsd = records.sumOf { it.providerCostUsd ?: 0.0 },
                    activeDurationMs = mission.updatedAt - mission.createdAt,
                    acceptanceTotal = mission.acceptanceCriteria.size,
                    acceptanceChecked = mission.acceptanceChecked.size,
                    lastActivityAt = events.lastOrNull()?.createdAt,
                )
            }
            .filterNotNull()
}
