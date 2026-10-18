package me.rerere.rikkahub.data.policy

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.ai.tools.ApprovalMode
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.preferences.ToolApprovalSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pins [DefaultPolicyEngine] — the P2 convergence of the auto-approval decision and the
 * token-budget read behind one interface.
 *
 * The matrix mirrors the pre-P2 ChatService lambda exactly (same sources, same pure
 * function, same evaluation order) so this test is also a regression guard: if the port
 * drifts, a case here breaks. Both sources are constructor-injected lambdas;
 * [HeadlessConversations] and [ToolApprovalAllowList] are in-memory singletons that work
 * off-Android, so the whole suite runs on the JVM without Koin or a Context.
 *
 * Uses [runBlocking] (not runTest) per the repo's existing test convention.
 */
class DefaultPolicyEngineTest {

    /** A snapshot source that always returns the given [ToolApprovalSnapshot]. */
    private fun fixedApproval(mode: ApprovalMode, blacklist: Set<String> = emptySet()) =
        ToolApprovalSnapshotSource { ToolApprovalSnapshot(blacklist = blacklist, mode = mode) }

    /** A budget source that always returns the given snapshot (or null). */
    private fun fixedBudget(snapshot: TokenBudgetTracker.Snapshot?) =
        BudgetSnapshotSource { snapshot }

    private fun engine(
        mode: ApprovalMode,
        blacklist: Set<String> = emptySet(),
        budget: TokenBudgetTracker.Snapshot? = null,
    ) = DefaultPolicyEngine(fixedApproval(mode, blacklist), fixedBudget(budget))

    @After
    fun clearGlobalSingletons() {
        // HeadlessConversations is a process-global singleton; clear any leaked IDs so a
        // failed test (whose finally didn't run) doesn't poison the next one. Safe in JVM
        // tests: prefs is null (init was never called), so persistIds is a no-op.
        HeadlessConversations.clearAll()
    }

    // ── AUTO mode ──────────────────────────────────────────────────────────────────

    @Test
    fun `AUTO approves an ordinary tool`() = runBlocking {
        val engine = engine(ApprovalMode.AUTO)
        assertTrue(engine.shouldAutoApproveTool(Uuid.random(), "battery"))
    }

    @Test
    fun `AUTO still asks for a hard-floor tool`() = runBlocking {
        val engine = engine(ApprovalMode.AUTO)
        // Pick a real member of the non-waivable floor. We skip mcp__-prefixed names
        // because those are waived in Auto and tested separately; the remaining members
        // (eval_javascript, skill_install_from_url, etc.) must always ask.
        val hardFloorTool = ToolApprovalDefaults.NO_ALWAYS_ALLOW
            .first { !it.startsWith(ToolApprovalDefaults.MCP_TOOL_PREFIX) }
        assertFalse(engine.shouldAutoApproveTool(Uuid.random(), hardFloorTool))
    }

    @Test
    fun `AUTO waives a relayed mcp tool`() = runBlocking {
        val engine = engine(ApprovalMode.AUTO)
        assertTrue(engine.shouldAutoApproveTool(Uuid.random(), "mcp__foo"))
    }

    // ── SEMI mode ──────────────────────────────────────────────────────────────────

    @Test
    fun `SEMI asks for a blacklisted tool`() = runBlocking {
        val engine = engine(ApprovalMode.SEMI, blacklist = setOf("termux_run_command"))
        assertFalse(engine.shouldAutoApproveTool(Uuid.random(), "termux_run_command"))
    }

    @Test
    fun `SEMI approves a tool not on the blacklist`() = runBlocking {
        val engine = engine(ApprovalMode.SEMI, blacklist = setOf("termux_run_command"))
        assertTrue(engine.shouldAutoApproveTool(Uuid.random(), "battery"))
    }

    // ── MANUAL mode ────────────────────────────────────────────────────────────────

    @Test
    fun `MANUAL asks for an ordinary tool`() = runBlocking {
        val engine = engine(ApprovalMode.MANUAL)
        assertFalse(engine.shouldAutoApproveTool(Uuid.random(), "battery"))
    }

    @Test
    fun `MANUAL approves a tool granted per-chat`() = runBlocking {
        val conv = Uuid.random()
        val tool = "battery"
        ToolApprovalAllowList.grantForChat(conv, tool)
        try {
            val engine = engine(ApprovalMode.MANUAL)
            assertTrue(engine.shouldAutoApproveTool(conv, tool))
        } finally {
            ToolApprovalAllowList.clearChat(conv)
        }
    }

    // ── Headless ───────────────────────────────────────────────────────────────────

    @Test
    fun `headless conversation auto-approves everything`() = runBlocking {
        val conv = Uuid.random()
        HeadlessConversations.mark(conv)
        try {
            // Even in Manual, headless → true for an ordinary tool.
            val manualEngine = engine(ApprovalMode.MANUAL)
            assertTrue(manualEngine.shouldAutoApproveTool(conv, "battery"))
            // And even for a blacklisted tool (SEMI + blacklisted, but headless overrides).
            val semiEngine = engine(ApprovalMode.SEMI, blacklist = setOf("termux_run_command"))
            assertTrue(semiEngine.shouldAutoApproveTool(conv, "termux_run_command"))
        } finally {
            HeadlessConversations.unmark(conv)
        }
    }

    // ── ask_user ───────────────────────────────────────────────────────────────────

    @Test
    fun `ask_user auto-approves only when headless`() = runBlocking {
        val conv = Uuid.random()
        val engine = engine(ApprovalMode.AUTO)

        // Non-headless: ask_user must pause for a human surface → false.
        assertFalse(engine.shouldAutoApproveTool(conv, "ask_user"))

        HeadlessConversations.mark(conv)
        try {
            // Headless: nobody to answer → true.
            assertTrue(engine.shouldAutoApproveTool(conv, "ask_user"))
        } finally {
            HeadlessConversations.unmark(conv)
        }
    }

    // ── Budget passthrough ─────────────────────────────────────────────────────────

    @Test
    fun `tokenBudgetSnapshot passes through the source snapshot`() = runBlocking {
        val fixed = TokenBudgetTracker.Snapshot(
            totals = TokenBudgetTracker.Totals(
                inputTokens = 100L,
                outputTokens = 50L,
                totalTokens = 150L,
                perMessageMax = 150L,
                messageCount = 3,
            ),
            softCap = 1000,
            hardCap = 2000,
            status = TokenBudgetTracker.BudgetStatus.UNDER_SOFT,
        )
        val engine = engine(ApprovalMode.MANUAL, budget = fixed)
        val result = engine.tokenBudgetSnapshot(Uuid.random())
        assertNotNull(result)
        assertEquals(fixed, result)
    }

    @Test
    fun `tokenBudgetSnapshot returns null when the source returns null`() = runBlocking {
        val engine = engine(ApprovalMode.MANUAL, budget = null)
        assertNull(engine.tokenBudgetSnapshot(null))
    }
}
