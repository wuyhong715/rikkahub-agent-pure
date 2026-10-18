package me.rerere.rikkahub.data.agentrun

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

private const val TAG = "AgentRunBootRecovery"

/**
 * Phase 24 — cross-pillar boot-recovery sweep for the unified `agent_runs` ledger.
 *
 * Run once per process start from [me.rerere.rikkahub.RikkaHubApp.onCreate], after Koin
 * init. The ledger is the source of truth for "which autonomous runs were mid-flight when
 * the process died": any row still in `running` / `awaiting_approval` / `queued` that
 * hasn't been touched in [AgentRunDefaults.STRANDED_THRESHOLD_MS] is stranded — the
 * process that owned it is gone (a live run updates its row well within 30 minutes).
 *
 * Stranded rows are flipped to [AgentRunStatus.process_lost] and a SINGLE aggregate
 * notification is posted (no per-row storm) — mirrors the Phase 9.5 cron stranded-row
 * sweep, generalised across all five autonomous paths.
 *
 * Best-effort throughout: a stuck or slow app start is worse than a skipped recovery
 * sweep, so failures are logged and never thrown.
 */
class AgentRunBootRecovery(
    private val repository: AgentRunRepository,
    /**
     * Aggregate-notification sink. Defaults to a real Android notification; injectable so
     * the sweep logic can be unit-tested on the JVM without a [Context]. Called at most
     * once per [runRecovery] invocation, only when at least one row was flipped.
     */
    private val notifyStranded: (List<AgentRun>) -> Unit = {},
    /**
     * P5 — resume hook, tried per stranded row before it is declared lost. Returns true
     * when the run was resumed all the way to completion (so the row is already terminal
     * and must NOT be flipped to process_lost). Default: never resume — the pre-P5 sweep.
     */
    private val resume: suspend (AgentRun) -> Boolean = { false },
) {

    /** Convenience constructor for production wiring — posts a real Android notification. */
    constructor(
        context: Context,
        repository: AgentRunRepository,
        resume: suspend (AgentRun) -> Boolean = { false },
    ) : this(
        repository = repository,
        notifyStranded = { stranded -> postAggregateNotification(context, stranded) },
        resume = resume,
    )

    /**
     * Walk the ledger, flip stranded rows to `process_lost`, fire one aggregate
     * notification if anything was flipped. Returns the count flipped (useful for tests).
     */
    suspend fun runRecovery(): Int {
        return runCatching {
            val cutoff = System.currentTimeMillis() - AgentRunDefaults.STRANDED_THRESHOLD_MS
            val stranded = repository.getStranded(cutoff)
            if (stranded.isEmpty()) {
                logSafe { Log.i(TAG, "runRecovery: no stranded runs") }
                return@runCatching 0
            }
            // P5 — give each stranded run a chance to resume from its checkpoint before
            // declaring it lost. The hook is serial on purpose: resumes re-enter the
            // generation machinery and a boot storm of parallel runs is the last thing a
            // just-started process needs.
            val lost = mutableListOf<AgentRun>()
            var resumed = 0
            for (run in stranded) {
                val resumable = runCatching { resume(run) }.getOrDefault(false)
                if (resumable) {
                    resumed++
                } else {
                    lost += run
                }
            }
            val flipped = repository.markAllProcessLost(lost.map { it.id })
            logSafe {
                Log.w(
                    TAG,
                    "runRecovery: resumed $resumed, flipped $flipped stranded run(s) to process_lost",
                )
            }
            if (flipped > 0) {
                runCatching { notifyStranded(stranded) }
                    .onFailure { logSafe { Log.w(TAG, "notifyStranded failed", it) } }
            }
            flipped
        }.onFailure { logSafe { Log.w(TAG, "runRecovery failed", it) } }
            .getOrDefault(0)
    }

    companion object {
        const val CHANNEL_ID = "rikkahub_agent_run_recovery"

        /** Fixed id so subsequent boots replace, not stack, the aggregate notification. */
        private const val AGGREGATE_NOTIF_ID = Int.MAX_VALUE - 101

        /**
         * One notification for the whole sweep, summarised by kind — never one per row.
         * Fixed notification id so a subsequent boot replaces the prior aggregate rather
         * than stacking them.
         */
        private fun postAggregateNotification(context: Context, stranded: List<AgentRun>) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            "Autonomous run recovery",
                            NotificationManager.IMPORTANCE_DEFAULT,
                        )
                    )
                }
                val byKind = stranded.groupingBy { AgentRunKind.fromWire(it.kind)?.wire ?: it.kind }
                    .eachCount()
                val breakdown = byKind.entries.joinToString(", ") { "${it.value} ${it.key}" }
                val title = if (stranded.size == 1) {
                    "An autonomous run was interrupted"
                } else {
                    "${stranded.size} autonomous runs were interrupted"
                }
                val text = "The app was killed mid-run ($breakdown). " +
                    "If this keeps happening, check the battery whitelist and foreground service settings."
                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)
                NotificationManagerCompat.from(context).notify(AGGREGATE_NOTIF_ID, builder.build())
            }.onFailure {
                // POST_NOTIFICATIONS not granted, or notifications restricted — non-fatal.
                logSafe { Log.w(TAG, "postAggregateNotification failed", it) }
            }
        }
    }
}

/**
 * Run a [android.util.Log] call inside a guard so JVM unit tests — where `android.util.Log`
 * is an unmocked stub that throws — don't crash before the code under test can return.
 * Mirrors `WorkflowActionRunner.logSafe`.
 */
private inline fun logSafe(block: () -> Unit) {
    runCatching { block() }
}
