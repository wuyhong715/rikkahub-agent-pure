package me.rerere.rikkahub.runtime.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnEventLogTest {
    @Test
    fun assignsMonotonicMetadataAndReturnsImmutableSnapshots() {
        var now = 100L
        val log = TurnEventLog { now++ }
        val first = log.append(TurnEvent.Started("turn-1"))
        val second = log.append(TurnEvent.ModelStep(step = 1, model = "test"))

        assertEquals(listOf(0L), first.events.map { it.sequence })
        assertEquals(listOf(0L, 1L), second.events.map { it.sequence })
        assertEquals(listOf(100L, 101L), second.events.map { it.timestampMs })
        assertEquals(1, first.events.size)
        assertTrue(first.events !== second.events)
    }

    @Test
    fun summaryPairsToolsByCallIdAndExposesPendingCalls() {
        val log = TurnEventLog()
        log.append(TurnEvent.Started("turn-1"))
        log.append(TurnEvent.ToolCalled("a", "read_file"))
        log.append(TurnEvent.ToolCalled("b", "write_file"))
        log.append(TurnEvent.ToolCompleted("a", "read_file", ok = true, outputTokens = 42))
        log.append(TurnEvent.Failed("tool", "IOException", retryable = true))
        log.append(TurnEvent.Recovered(fromSequence = 3, reason = "retry"))

        val summary = log.summary()
        assertEquals(2, summary.toolCallCount)
        assertEquals(1, summary.completedToolCallCount)
        assertEquals(listOf("b"), summary.pendingToolCallIds)
        assertEquals(1, summary.failureCount)
        assertEquals(1, summary.recoveryCount)
    }

    @Test
    fun compactionAndFinishRemainRuntimeEvents() {
        val log = TurnEventLog()
        log.append(TurnEvent.CompactionStarted("context_pressure", sourceEventCount = 20))
        log.append(TurnEvent.CompactionFinished(retainedEventCount = 8, summaryTokens = 300))
        log.append(TurnEvent.Finished(success = true))

        val summary = log.summary()
        assertEquals(1, summary.compactionCount)
        assertEquals(3, summary.eventCount)
        assertFalse(summary.pendingToolCallIds.isNotEmpty())
    }

    @Test
    fun clearResetsSequenceForAReuseableTurnRecorder() {
        val log = TurnEventLog()
        log.append(TurnEvent.Started("old"))
        log.clear()
        val snapshot = log.append(TurnEvent.Started("new"))

        assertEquals(0L, snapshot.events.single().sequence)
        assertEquals("new", (snapshot.events.single() as TurnEvent.Started).turnId)
    }
}
