package me.rerere.rikkahub.workflow.trigger

import me.rerere.rikkahub.workflow.model.TriggerSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * JVM unit tests for [TimeCronSchedule].
 *
 * Two properties matter here and both were broken:
 *  - **stability**: the same clock, called twice, must give the same answer. MissionScheduler
 *    persists this value into `missions.next_run_at` and writes it back on every reconcile; a
 *    value that moves on every call turns that write into a self-sustaining ~3 Hz loop.
 *  - **phase persistence**: opening the app must not restart an @daily countdown, or a user who
 *    opens the app every day never sees the task fire.
 */
class TimeCronScheduleTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun hourly() = TriggerSpec.TimeCron(cron = "@hourly")

    @Test
    fun `a periodic schedule answers the same value twice`() {
        val clock = at(25, 8, 0)
        // Reconcile #1: nothing is persisted yet, so the countdown starts now.
        val first = TimeCronSchedule.nextFireMs(hourly(), zone, clock)
        // The scheduler writes that value into `missions.next_run_at`, which re-emits the flow
        // and runs reconcile #2 1.5s later. Reading the persisted value back must reproduce it
        // exactly, or the write re-triggers itself forever.
        val second = TimeCronSchedule.nextFireMs(hourly(), zone, clock + 1_500, scheduledNextMs = first)
        assertEquals(first, second)
    }

    @Test
    fun `the next fire keeps its phase from the last one`() {
        val fired = at(25, 8, 0)
        val next = TimeCronSchedule.nextFireMs(
            spec = hourly(),
            zone = zone,
            nowMs = fired + 3 * 60_000,
            lastFiredAtMs = fired,
        )
        assertEquals(at(25, 9, 0), next)
    }

    @Test
    fun `a missed occurrence advances by whole periods instead of shifting the phase`() {
        // Armed for 08:00, the device was asleep until 10:20: the next fire is 11:00 (the phase
        // survives), not 11:20 (a fresh countdown from "now").
        val next = TimeCronSchedule.nextFireMs(
            spec = hourly(),
            zone = zone,
            nowMs = at(25, 10, 20),
            scheduledNextMs = at(25, 8, 0),
        )
        assertEquals(at(25, 11, 0), next)
    }

    @Test
    fun `a reopened app reuses the persisted next run`() {
        // @daily armed at 00:05 for the next midnight; the app is reopened 12h later. The
        // countdown must resume, not restart at now + 24h.
        val persistedNext = at(26, 0, 5)
        val next = TimeCronSchedule.nextFireMs(
            spec = TriggerSpec.TimeCron(cron = "@daily"),
            zone = zone,
            nowMs = at(25, 12, 0),
            scheduledNextMs = persistedNext,
        )
        assertEquals(persistedNext, next)
    }

    @Test
    fun `a value already in the past is skipped forward, never returned`() {
        val now = at(25, 8, 0)
        val next = TimeCronSchedule.nextFireMs(
            spec = hourly(),
            zone = zone,
            nowMs = now,
            scheduledNextMs = now,
        )
        assertEquals(at(25, 9, 0), next)
    }

    @Test
    fun `time of day is absolute and stable`() {
        val spec = TriggerSpec.TimeCron(timeOfDay = "09:30")
        val first = TimeCronSchedule.nextFireMs(spec, zone, at(25, 8, 0))
        val second = TimeCronSchedule.nextFireMs(spec, zone, at(25, 8, 0) + 1_500)
        assertEquals(at(25, 9, 30), first)
        assertEquals(first, second)
    }

    @Test
    fun `time of day rolls to tomorrow once it has passed`() {
        val next = TimeCronSchedule.nextFireMs(
            spec = TriggerSpec.TimeCron(timeOfDay = "09:30"),
            zone = zone,
            nowMs = at(25, 10, 0),
        )
        assertEquals(at(26, 9, 30), next)
    }

    @Test
    fun `days of week pick the next allowed day`() {
        // 2026-10-25 is a Sunday; "Mondays 09:00" must land on the 26th.
        val next = TimeCronSchedule.nextFireMs(
            spec = TriggerSpec.TimeCron(timeOfDay = "09:00", daysOfWeek = listOf(1)),
            zone = zone,
            nowMs = at(25, 12, 0),
        )
        assertEquals(at(26, 9, 0), next)
    }

    @Test
    fun `every 30 minutes keeps the half-hour phase`() {
        val fired = at(25, 8, 0)
        val next = TimeCronSchedule.nextFireMs(
            spec = TriggerSpec.TimeCron(cron = "@every 30m"),
            zone = zone,
            nowMs = fired + 5 * 60_000,
            lastFiredAtMs = fired,
        )
        assertEquals(at(25, 8, 30), next)
    }

    @Test
    fun `a sub-minute every is not periodic`() {
        assertEquals(null, TimeCronSchedule.periodMs(TriggerSpec.TimeCron(cron = "@every 30s")))
        assertEquals(60_000L, TimeCronSchedule.periodMs(TriggerSpec.TimeCron(cron = "@every 60s")))
    }
}
