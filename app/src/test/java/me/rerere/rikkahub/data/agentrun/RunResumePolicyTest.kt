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
    fun `a second death hands off to the user`() {
        assertEquals(
            RunResumeDecision.HAND_OFF,
            RunResumePolicy.decide(
                kind = "cron",
                checkpoint = checkpoint(attempts = RunCheckpointDefaults.MAX_AUTO_RESUME_ATTEMPTS),
                conversationExists = true,
            ),
        )
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
