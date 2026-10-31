package me.rerere.rikkahub.data.agentrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5 — the resume decision matrix. Pure function, so every branch runs on a bare JVM.
 */
class RunResumePolicyTest {

    private fun checkpoint(
        runId: String = "run-1",
        phase: String = "GENERATING",
        stepIndex: Int = 3,
        attempts: Int = 0,
        pendingTools: String? = null,
    ) = RunCheckpointEntity(
        runId = runId,
        conversationId = "conv-1",
        kind = "cron",
        phase = phase,
        stepIndex = stepIndex,
        messageCount = 9,
        pendingTools = pendingTools,
        attempts = attempts,
        updatedAtMs = 1L,
    )

    @Test
    fun `no checkpoint means nothing to resume`() {
        assertEquals(
            RunResumeDecision.NOT_RESUMABLE,
            RunResumePolicy.decide(kind = "cron", checkpoint = null, conversationExists = true),
        )
    }

    @Test
    fun `a missing conversation means nothing to resume`() {
        assertEquals(
            RunResumeDecision.NOT_RESUMABLE,
            RunResumePolicy.decide(kind = "cron", checkpoint = checkpoint(), conversationExists = false),
        )
    }

    @Test
    fun `cron resumes automatically on the first interruption`() {
        assertEquals(
            RunResumeDecision.AUTO_RESUME,
            RunResumePolicy.decide(kind = "cron", checkpoint = checkpoint(), conversationExists = true),
        )
    }

    @Test
    fun `mission rounds resume automatically while the mission still owns them`() {
        assertEquals(
            RunResumeDecision.AUTO_RESUME,
            RunResumePolicy.decide(
                kind = "mission",
                checkpoint = checkpoint(),
                conversationExists = true,
                runId = "run-1",
                mission = MissionClaim(
                    status = "ACTIVE",
                    executionState = "RUNNING",
                    lastRunId = "run-1",
                ),
            ),
        )
    }

    @Test
    fun `a mission that no longer owns the run is never resumed`() {
        val owned = MissionClaim(status = "ACTIVE", executionState = "RUNNING", lastRunId = "run-1")
        val cases = mapOf(
            "mission done" to owned.copy(status = "DONE"),
            "mission archived" to owned.copy(status = "ARCHIVED"),
            "mission parked idle (user stop)" to owned.copy(executionState = "IDLE"),
            "a newer round took over" to owned.copy(lastRunId = "run-2"),
            "mission gone" to null,
        )
        for ((label, claim) in cases) {
            assertEquals(
                label,
                RunResumeDecision.NOT_RESUMABLE,
                RunResumePolicy.decide(
                    kind = "mission",
                    checkpoint = checkpoint(),
                    conversationExists = true,
                    runId = "run-1",
                    mission = claim,
                ),
            )
        }
    }

    @Test
    fun `a mission run without a run id is never resumed`() {
        assertEquals(
            RunResumeDecision.NOT_RESUMABLE,
            RunResumePolicy.decide(
                kind = "mission",
                checkpoint = checkpoint(),
                conversationExists = true,
                mission = MissionClaim(
                    status = "ACTIVE",
                    executionState = "RUNNING",
                    lastRunId = "run-1",
                ),
            ),
        )
    }

    @Test
    fun `cron runs do not consult a mission claim`() {
        assertEquals(
            RunResumeDecision.AUTO_RESUME,
            RunResumePolicy.decide(
                kind = "cron",
                checkpoint = checkpoint(),
                conversationExists = true,
                runId = "run-1",
                mission = null,
            ),
        )
    }

    @Test
    fun `a second death hands off to the user`() {
        for (kind in listOf("cron", "mission")) {
            assertEquals(
                "kind=$kind",
                RunResumeDecision.HAND_OFF,
                RunResumePolicy.decide(
                    kind = kind,
                    checkpoint = checkpoint(attempts = RunCheckpointDefaults.MAX_AUTO_RESUME_ATTEMPTS),
                    conversationExists = true,
                    runId = "run-1",
                    mission = if (kind == "mission") {
                        MissionClaim(status = "ACTIVE", executionState = "RUNNING", lastRunId = "run-1")
                    } else {
                        null
                    },
                ),
            )
        }
    }

    @Test
    fun `kinds with unreplayable side effects always hand off`() {
        for (kind in listOf("telegram", "subagent", "workflow", "external_automation")) {
            assertEquals(
                "kind=$kind",
                RunResumeDecision.HAND_OFF,
                RunResumePolicy.decide(kind = kind, checkpoint = checkpoint(), conversationExists = true),
            )
        }
    }

    @Test
    fun `chat turns are never auto-resumed`() {
        assertEquals(
            RunResumeDecision.HAND_OFF,
            RunResumePolicy.decide(kind = RunResumePolicy.CHAT_KIND, checkpoint = checkpoint(), conversationExists = true),
        )
    }

    @Test
    fun `breakpoint text names the step and the unexecuted tools`() {
        val described = RunResumePolicy.describeBreakpoint(
            checkpoint(phase = "EXECUTING_TOOL", pendingTools = "[\"call-a\",\"call-b\"]")
        )
        assertTrue(described.contains("step 3"))
        assertTrue(described.contains("call-a"))
        assertTrue(described.contains("call-b"))
    }

    @Test
    fun `breakpoint text still works without pending tools`() {
        val described = RunResumePolicy.describeBreakpoint(checkpoint())
        assertTrue(described.contains("step 3"))
        assertTrue(described.contains("generating"))
    }
}
