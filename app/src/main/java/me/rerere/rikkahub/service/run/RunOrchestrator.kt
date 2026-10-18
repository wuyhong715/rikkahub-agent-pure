package me.rerere.rikkahub.service.run

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.runtime.util.JsonInstant

/**
 * P6 — multi-run orchestration.
 *
 * One parent turn splits its work into N child runs and runs them in parallel under a
 * hard concurrency cap. Every child is a first-class Run: it gets its own `agent_runs`
 * row (parent_run_id = the caller's run), its own conversation, its own journal scope and
 * — through P5 — its own checkpoint. The parent gets one aggregate result instead of N
 * ad-hoc dispatches with no shared identity.
 *
 * The service is deliberately resolved through a provider (`runServiceProvider`): the
 * orchestrator is injected into [me.rerere.rikkahub.service.ChatService] to build the
 * `mission_dispatch` tool, while ChatService is what backs `RunService` in production.
 * Holding a lambda breaks that cycle without weakening the API.
 *
 * Failure model: a child that cannot be submitted, or whose run fails/times out, does not
 * abort the batch — it is reported as an outcome in the aggregate, because partial
 * progress is the normal case for work that is split across devices of varying
 * reliability.
 */
class RunOrchestrator(
    private val runServiceProvider: () -> RunService,
    private val runEventRepository: RunEventRepository,
) {

    /** One child's outcome, in submission order. */
    data class ChildOutcome(
        val index: Int,
        val label: String,
        val runId: String?,
        val conversationId: String?,
        val status: RunStatus?,
        val failure: String? = null,
    ) {
        val succeeded: Boolean get() = status == RunStatus.COMPLETED
    }

    /** Aggregate of a batch, in submission order. */
    data class BatchOutcome(
        val children: List<ChildOutcome>,
        val requested: Int,
    ) {
        val completed: Int get() = children.count { it.succeeded }
        val allCompleted: Boolean get() = children.isNotEmpty() && children.all { it.succeeded }
    }

    /**
     * Submit [requests] concurrently (never more than [maxConcurrency] in flight), wait for
     * every child to reach a terminal state, and return the aggregate.
     *
     * Requests are normalised: the batch's [missionId] / [parentRunId] win when they are
     * set, and children default to [AgentRunKind.SubAgent] so the ledger shows them as
     * dispatched work rather than as unrelated autonomous fires.
     */
    suspend fun dispatch(
        requests: List<RunRequest>,
        missionId: String? = null,
        parentRunId: String? = null,
        maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    ): BatchOutcome = coroutineScope {
        val cap = maxConcurrency.coerceIn(1, MAX_CONCURRENCY)
        val semaphore = Semaphore(cap)
        val outcomes = requests.mapIndexed { index, request ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    dispatchOne(
                        index = index,
                        request = request.copy(
                            missionId = missionId ?: request.missionId,
                            parentRunId = parentRunId ?: request.parentRunId,
                        ),
                    )
                }
            }
        }.awaitAll()

        journalAggregate(missionId, parentRunId, BatchOutcome(outcomes, requests.size))
        BatchOutcome(outcomes, requests.size)
    }

    private suspend fun dispatchOne(index: Int, request: RunRequest): ChildOutcome {
        val label = request.title ?: request.prompt.take(40)
        val runService = runServiceProvider()
        val handle = runCatching { runService.submit(request) }.getOrNull()
            ?: return ChildOutcome(
                index = index,
                label = label,
                runId = null,
                conversationId = null,
                status = null,
                failure = "submit failed",
            )

        journal(
            type = RunEventType.SUBAGENT_DISPATCHED,
            missionId = request.missionId,
            conversationId = handle.conversationId.toString(),
            runId = handle.id,
            title = label,
            payload = buildJsonObject {
                put("child_run_id", handle.id)
                request.parentRunId?.let { put("parent_run_id", it) }
            },
        )

        val result = runCatching { runService.awaitTerminal(handle) }.getOrNull()
        journal(
            type = RunEventType.SUBAGENT_FINISHED,
            missionId = request.missionId,
            conversationId = handle.conversationId.toString(),
            runId = handle.id,
            title = result?.status?.name ?: "FAILED",
            payload = result?.failure?.let { buildJsonObject { put("failure", it) } },
        )

        return ChildOutcome(
            index = index,
            label = label,
            runId = handle.id,
            conversationId = handle.conversationId.toString(),
            status = result?.status,
            failure = result?.failure,
        )
    }

    private suspend fun journalAggregate(
        missionId: String?,
        parentRunId: String?,
        batch: BatchOutcome,
    ) {
        journal(
            type = RunEventType.MILESTONE,
            missionId = missionId,
            conversationId = null,
            runId = parentRunId,
            title = "batch: ${batch.completed}/${batch.requested} completed",
            payload = buildJsonObject {
                put("completed", JsonPrimitive(batch.completed))
                put("requested", JsonPrimitive(batch.requested))
                parentRunId?.let { put("parent_run_id", it) }
            },
        )
    }

    private suspend fun journal(
        type: RunEventType,
        missionId: String?,
        conversationId: String?,
        runId: String?,
        title: String,
        payload: kotlinx.serialization.json.JsonObject?,
    ) {
        runCatching {
            runEventRepository.emit(
                type = type,
                title = title,
                payloadJson = payload?.let { JsonInstant.encodeToString(it) },
                missionId = missionId,
                conversationId = conversationId,
                runId = runId,
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENCY = 3
        const val MAX_CONCURRENCY = 6
    }
}
