package me.rerere.rikkahub.subagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-15 — who may read / resume / cancel a sub-agent run.
 *
 * The id is the only thing a tool call carries, so these rules are the access control for
 * `subagent_get` / `subagent_cancel` / `subagent_send` / `subagent_list`. Failing closed on a
 * missing caller context matters as much as the positive cases: a headless path with no
 * conversation must not inherit every run in the process.
 */
class SubAgentRunOwnershipTest {

    private fun run(
        parent: String? = "chat-a",
        root: String? = "chat-a",
        status: SubAgentStatus = SubAgentStatus.SUCCEEDED,
    ): SubAgentRun = SubAgentRun(
        id = "r1",
        parentChatId = parent,
        rootChatId = root,
        parentAssistantId = "asst",
        label = "label",
        task = "task",
        modelId = null,
        tools = null,
        runInBackground = false,
        timeoutSeconds = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
        maxTrips = SubAgentDefaults.DEFAULT_MAX_TRIPS,
        status = status,
        startedAtMs = 0L,
    )

    @Test
    fun `the dispatching conversation owns its run`() {
        assertTrue(run().isOwnedBy("chat-a"))
    }

    @Test
    fun `the tree root owns a nested run`() {
        assertTrue(run(parent = "chat-child", root = "chat-root").isOwnedBy("chat-root"))
    }

    @Test
    fun `another conversation can neither see nor resume it`() {
        assertFalse(run().isOwnedBy("chat-b"))
        assertFalse(run(parent = "chat-child", root = "chat-root").isOwnedBy("chat-other"))
    }

    @Test
    fun `a run with no lineage is owned by nobody`() {
        assertFalse(run(parent = null, root = null).isOwnedBy("chat-a"))
    }

    @Test
    fun `no caller context fails closed`() {
        assertFalse(run().isOwnedBy(null))
        assertFalse(run().isOwnedBy(""))
    }

    @Test
    fun `active covers exactly queued and running`() {
        assertTrue(run(status = SubAgentStatus.PENDING).isActive)
        assertTrue(run(status = SubAgentStatus.RUNNING).isActive)
        assertFalse(run(status = SubAgentStatus.SUCCEEDED).isActive)
        assertFalse(run(status = SubAgentStatus.CANCELLED).isActive)
    }
}
