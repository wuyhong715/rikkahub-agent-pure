package me.rerere.rikkahub.workflow.trigger

import me.rerere.rikkahub.service.CronExpressionParser
import me.rerere.rikkahub.workflow.model.TriggerSpec
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When a [TriggerSpec.TimeCron] fires next.
 *
 * Pure and Android-free by construction, for two reasons:
 *
 * 1. **Testability.** The schedule maths is where the off-by-one and the "it never fires" bugs
 *    live; none of that needs a Context or a WorkManager.
 * 2. **Stability.** The answer for a periodic schedule must be the *same* on two calls a
 *    millisecond apart. MissionScheduler persists it into `missions.next_run_at` and writes that
 *    column back from every reconcile of the mission flow - and the write itself re-emits the
 *    flow. With a drifting "now + period", the dedupe never matches, so the write, the emission
 *    and the next reconcile form a permanent loop at the debounce rate (~3 Hz) while the app is
 *    open. Anchoring on the last real fire (or on the value already scheduled) makes the answer
 *    constant between fires, which is exactly what the dedupe needs.
 */
object TimeCronSchedule {

    /** The period of a recurring spec, or null when the schedule is not periodic. */
    fun periodMs(spec: TriggerSpec.TimeCron): Long? {
        if (!spec.timeOfDay.isNullOrBlank()) return 24L * 60 * 60 * 1000
        val cron = spec.cron?.trim() ?: return null
        // @every Ns
        val every = Regex("^@every\\s+(\\d+)([smhd])$").find(cron)
        if (every != null) {
            val n = every.groupValues[1].toLong()
            val unit = every.groupValues[2]
            return when (unit) {
                // Sub-minute not supported (matches CronExpressionParser's @every Ns floor) —
                // without this, WorkflowJson.validate() treats any positive n as valid since this
                // function never returns null for "s", and the schedule silently degrades to the
                // one-shot path's 60s delay floor instead of surfacing a validation error.
                "s" -> if (n < 60) null else n * 1000
                "m" -> n * 60 * 1000
                "h" -> n * 60 * 60 * 1000
                "d" -> n * 24 * 60 * 60 * 1000
                else -> null
            }
        }
        return when (cron) {
            "@hourly" -> 60L * 60 * 1000
            "@daily", "@midnight" -> 24L * 60 * 60 * 1000
            "@weekly" -> 7L * 24 * 60 * 60 * 1000
            else -> null  // 5-field cron — falls back to the absolute path below
        }
    }

    /**
     * The next fire time in epoch millis.
     *
     * @param nowMs            the clock at call time — only used to *place* the next fire, never
     *                         to define the period's phase.
     * @param lastFiredAtMs    when this schedule last actually fired, if it ever has.
     * @param scheduledNextMs  the next fire already persisted for this schedule, if any. Preferred
     *                         over [lastFiredAtMs]: it is the value a previous call computed, so
     *                         reusing it keeps successive calls in agreement and survives a
     *                         process restart (which is what made an @daily task's countdown
     *                         reset to a full day every time the app was reopened).
     */
    fun nextFireMs(
        spec: TriggerSpec.TimeCron,
        zone: ZoneId,
        nowMs: Long,
        lastFiredAtMs: Long? = null,
        scheduledNextMs: Long? = null,
    ): Long {
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)

        // time_of_day + optional days_of_week — absolute, so already stable between calls.
        if (!spec.timeOfDay.isNullOrBlank()) {
            val (h, m) = spec.timeOfDay.split(":").let { it[0].toInt() to it[1].toInt() }
            var candidate = now.toLocalDate().atTime(LocalTime.of(h, m)).atZone(zone)
            if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
            if (spec.daysOfWeek.isNotEmpty()) {
                val allowed = spec.daysOfWeek.map { isoDow(it) }.toSet()
                var hops = 0
                while (candidate.dayOfWeek !in allowed && hops < 8) {
                    candidate = candidate.plusDays(1); hops++
                }
            }
            return candidate.toInstant().toEpochMilli()
        }

        // Periodic (@every, @hourly, @daily, @weekly): anchor, don't drift.
        periodMs(spec)?.let { period ->
            // Phase, in order of trust: what was already scheduled → the last real fire → now.
            var next = scheduledNextMs ?: lastFiredAtMs?.plus(period) ?: (nowMs + period)
            if (next <= nowMs) {
                // Missed one or more occurrences (app closed, device asleep): skip forward whole
                // periods so the phase is preserved instead of restarting the countdown.
                val missed = (nowMs - next) / period + 1
                next += missed * period
            }
            return next
        }

        // 5-field cron: the exact next execution via the shared parser (same dialect as scheduled
        // jobs). Without this, "0 9 * * 1" style expressions silently degraded to hourly fires.
        spec.cron?.trim()?.takeIf { it.isNotBlank() }?.let { cron ->
            CronExpressionParser.parse(cron).getOrNull()?.let { parsed ->
                CronExpressionParser.nextExecution(parsed, now)?.let { return it.toInstant().toEpochMilli() }
            }
        }

        // Fallback for an unsupported cron — fire roughly hourly so the user gets something
        // useful even with an exotic schedule. The worker re-evaluates on every fire.
        return scheduledNextMs?.takeIf { it > nowMs } ?: (nowMs + 60L * 60 * 1000)
    }

    private fun isoDow(iso: Int): DayOfWeek = when (iso) {
        1 -> DayOfWeek.MONDAY
        2 -> DayOfWeek.TUESDAY
        3 -> DayOfWeek.WEDNESDAY
        4 -> DayOfWeek.THURSDAY
        5 -> DayOfWeek.FRIDAY
        6 -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }
}
