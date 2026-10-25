package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.workflow.model.TriggerSpec
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Phase 12 — time / cron family.
 *
 * Reuses WorkManager (same backend as scheduled jobs) but with workflow-scoped unique-work
 * names so one workflow's schedule doesn't collide with another's, and so unsync removes
 * exactly the disabled workflow's worker without touching anything else.
 *
 * v1 supports the time_of_day + days_of_week subset. The `cron` field is also accepted
 * (per spec — same dialect as scheduled jobs): `@every Ns`, `@hourly`, `@daily`, `@weekly`
 * map to fixed periods, and arbitrary 5-field cron runs on the one-shot chain with the next
 * fire computed exactly via [me.rerere.rikkahub.service.CronExpressionParser] (shared with
 * scheduled jobs). The validator rejects anything neither path can handle.
 *
 * We use [WorkflowTimeCronWorker] (separate file) which receives the workflow id and
 * dispatches into the engine.
 */
internal class TimeCronTriggerFamily(
    private val context: Context,
    private val scope: CoroutineScope,
    /**
     * 批 B — cold-start fallback. When WorkManager wakes the worker before the registry's
     * repo flow has emitted (post-boot), [lastSnapshot] is empty; this resolves the binding
     * straight from whichever store owns the id (missions in batch B, workflows for the
     * legacy registry). Null return = unknown / not armed.
     */
    private val resolver: (suspend (String) -> TriggerBinding?)? = null,
    /**
     * 批 B — callback available even before the first [sync]. A WorkManager cold start can
     * run the worker before the mission flow emits; without this the fire would be dropped.
     */
    private val fallbackCallback: TriggerFireCallback? = null,
) : WorkflowTriggerFamily {

    override val name = "time_cron"

    @Volatile private var lastSnapshot: List<TriggerBinding> = emptyList()
    @Volatile private var fireCallback: TriggerFireCallback? = null

    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.TimeCron

    override suspend fun sync(matching: List<TriggerBinding>, callback: TriggerFireCallback) {
        fireCallback = callback
        val previous = lastSnapshot.associateBy { it.id }
        val current = matching.associateBy { it.id }
        // Cancel removed
        for (id in previous.keys - current.keys) {
            cancelWork(id)
        }
        // Schedule added or changed
        for ((id, wf) in current) {
            val prev = previous[id]
            if (prev == null
                || prev.trigger != wf.trigger
                || prev.updatedAtMs != wf.updatedAtMs
            ) {
                scheduleWork(wf)
            }
        }
        lastSnapshot = matching
    }

    override suspend fun shutdown() {
        for (wf in lastSnapshot) cancelWork(wf.id)
        lastSnapshot = emptyList()
        fireCallback = null
    }

    fun cancelWork(workflowId: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName(workflowId)) }
            .onFailure { Log.w(TAG, "time_cron: cancel work failed for $workflowId", it) }
    }

    private fun scheduleWork(wf: TriggerBinding) {
        val spec = wf.trigger as? TriggerSpec.TimeCron ?: return
        val zone = spec.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

        // Periodic path: time_of_day-based or @every — both reduce to a 24h period for
        // time_of_day, or the parsed N-second period for @every. WorkManager's smallest
        // period is 15 minutes, so very-short cycles fall back to one-shot rescheduling
        // from the worker (worker re-enqueues itself).
        val periodMs = derivePeriodMs(spec)
        if (periodMs != null && periodMs >= 15 * 60 * 1000L) {
            // A next run that is already persisted wins over "now + period". This family keeps
            // its snapshot in memory only, so on a cold start every binding looks new: recomputing
            // from `now` re-armed an @daily task with a fresh 24h delay on every app launch, and a
            // user who opens the app daily therefore never saw it fire.
            val now = System.currentTimeMillis()
            val nextFireMs = wf.nextRunAtMs?.takeIf { it > 0 } ?: computeNextFireMs(spec, zone, now)
            val delay = (nextFireMs - now).coerceAtLeast(0L)
            val req = PeriodicWorkRequestBuilder<WorkflowTimeCronWorker>(periodMs, TimeUnit.MILLISECONDS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_WORKFLOW_ID to wf.id))
                .setConstraints(Constraints.NONE)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    workName(wf.id), ExistingPeriodicWorkPolicy.REPLACE, req,
                )
            }.onFailure { Log.w(TAG, "time_cron: periodic enqueue failed for ${wf.id}", it) }
            return
        }

        // One-shot path: schedule the next fire; the worker re-enqueues itself on completion.
        val now = System.currentTimeMillis()
        val nextFireMs = wf.nextRunAtMs?.takeIf { it > 0 } ?: computeNextFireMs(spec, zone, now)
        val delay = (nextFireMs - now).coerceAtLeast(60_000L)
        val req = OneTimeWorkRequestBuilder<WorkflowTimeCronWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_WORKFLOW_ID to wf.id))
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(wf.id), ExistingWorkPolicy.REPLACE, req,
            )
        }.onFailure { Log.w(TAG, "time_cron: one-shot enqueue failed for ${wf.id}", it) }
    }

    /** Internal — fires the owner then re-enqueues if needed. Called from worker. */
    suspend fun onWorkerFired(workflowId: String) {
        val cb = fireCallback ?: fallbackCallback ?: return
        // Post-boot or post-process-death race: WorkManager wakes the worker before
        // registry.start() has emitted from the repo's flow, leaving `lastSnapshot`
        // empty. Fall back to a direct resolver lookup so the fire isn't silently
        // dropped. The mission scheduler still gates status / cooldown / daily cap.
        val wf = lastSnapshot.firstOrNull { it.id == workflowId }
            ?: resolver?.invoke(workflowId)
            ?: return
        // days_of_week gate. The time_of_day path runs as a fixed 24h PeriodicWorkRequest
        // which fires every day; the day restriction is only honoured here. Without this
        // gate a "Mondays 09:00" workflow fires daily after its first Monday.
        val spec = wf.trigger as? TriggerSpec.TimeCron
        if (spec != null && !spec.timeOfDay.isNullOrBlank() && spec.daysOfWeek.isNotEmpty()) {
            val zone = spec.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: ZoneId.systemDefault()
            val today = ZonedDateTime.now(zone).dayOfWeek
            if (today !in spec.daysOfWeek.map { isoDow(it) }) {
                Log.d(TAG, "time_cron: $workflowId skipped, $today not in days_of_week")
                return
            }
        }
        scope.launch(Dispatchers.IO) {
            runCatching { cb.onFire(wf.id, wf.trigger) }
                .onFailure { Log.w(TAG, "time_cron: fire callback failed for $workflowId", it) }
        }
        // For one-shot path (period < 15min or null), re-enqueue with the next fire.
        val periodMs = (wf.trigger as? TriggerSpec.TimeCron)?.let { derivePeriodMs(it) }
        if (periodMs == null || periodMs < 15 * 60 * 1000L) {
            scheduleWork(wf)
        }
    }

    companion object {
        private const val TAG = "WorkflowTrigger"
        const val KEY_WORKFLOW_ID = "workflow_id"
        fun workName(workflowId: String) = "wf_timecron_$workflowId"

        /**
         * Returns null for arbitrary cron (one-shot path) or the period in ms for the
         * supported subset. Daily HH:mm = 24h. @every Ns = N seconds. @hourly = 1h.
         * @daily = 24h. days_of_week with time_of_day still uses 24h period (worker's
         * fire skips when day doesn't match).
         */
        fun derivePeriodMs(spec: TriggerSpec.TimeCron): Long? = TimeCronSchedule.periodMs(spec)

        /**
         * Compute the next fire time, deferring to [TimeCronSchedule] so the value is stable
         * between calls. See that object for why stability is load-bearing.
         */
        fun computeNextFireMs(spec: TriggerSpec.TimeCron, zone: ZoneId, nowMs: Long): Long =
            TimeCronSchedule.nextFireMs(spec, zone, nowMs)

        private fun isoDow(iso: Int): DayOfWeek = when (iso) {
            1 -> DayOfWeek.MONDAY; 2 -> DayOfWeek.TUESDAY; 3 -> DayOfWeek.WEDNESDAY
            4 -> DayOfWeek.THURSDAY; 5 -> DayOfWeek.FRIDAY; 6 -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
    }
}
