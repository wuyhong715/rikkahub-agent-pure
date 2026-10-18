package me.rerere.rikkahub.data.policy

import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
import me.rerere.rikkahub.data.ai.tools.ToolApprovalPolicy
import me.rerere.rikkahub.data.preferences.ToolApprovalSnapshot
import kotlin.uuid.Uuid

/**
 * Policy Engine — the single decision point for "may this execution do X" (P2 of the
 * harness re-architecture).
 *
 * Before P2 the auto-approval decision was assembled inline inside ChatService's
 * `isToolAutoApproved` lambda, and the token-budget read lived inside the
 * `check_token_usage` tool. Both are the same kind of question — "given the policy
 * inputs, what is the decision/reading" — so they converge here behind one interface.
 *
 * **P2 is behaviour-preserving**: [DefaultPolicyEngine.shouldAutoApproveTool] is the
 * exact port of the pre-P2 ChatService lambda (same sources, same pure function,
 * same order of evaluation). The value is structural: surfaces (P3) and the future
 * runtimes (P4+) consult this engine instead of re-assembling policy inputs.
 *
 * Later phases grow the context (mission/run-scoped overrides, device signals like
 * battery/network) without changing call sites.
 */
interface PolicyEngine {

    /**
     * Whether [toolName] may run without prompting the user in [conversationId].
     *
     * Semantics (identical to the pre-P2 ChatService lambda):
     * `ask_user` auto-approves only headless; headless conversations auto-approve
     * everything (the *refusal* floor for unattended runs lives in
     * HeadlessToolApprovalPolicy, enforced separately); AUTO mode waives relayed
     * `mcp__*` tools; the named hard floor always asks; an in-memory per-chat grant
     * suppresses the prompt; otherwise the mode decides (AUTO approve / SEMI consult
     * blacklist / MANUAL ask).
     */
    suspend fun shouldAutoApproveTool(conversationId: Uuid, toolName: String): Boolean

    /**
     * Advisory token-budget reading for a conversation (soft/hard caps vs running
     * totals). Purely informational — enforcement stays with the model's
     * self-throttle and the watchdog. Returns null when no conversation can be
     * resolved.
     */
    suspend fun tokenBudgetSnapshot(conversationId: Uuid?): TokenBudgetTracker.Snapshot?
}

/**
 * Supplier seam for the approval prefs (blacklist + mode). The real source is
 * `ToolApprovalPreferences.snapshot()`, which needs a Context — hiding it behind this
 * seam keeps [DefaultPolicyEngine] JVM-testable.
 */
fun interface ToolApprovalSnapshotSource {
    suspend fun snapshot(): ToolApprovalSnapshot
}

/** Same trick for the budget read, whose real implementation needs SettingsStore +
 *  ConversationRepository (see the DI wiring). */
fun interface BudgetSnapshotSource {
    suspend fun snapshot(conversationId: Uuid?): TokenBudgetTracker.Snapshot?
}

/**
 * Default [PolicyEngine]: assembles the async inputs from the injected sources and
 * delegates the decision to the pure, unit-tested [ToolApprovalPolicy.shouldAutoApprove].
 *
 * JVM-testable: both sources are constructor-injected lambdas; [HeadlessConversations]
 * and [ToolApprovalAllowList] are in-memory singletons that work off-Android.
 */
class DefaultPolicyEngine(
    private val approvalSnapshots: ToolApprovalSnapshotSource,
    private val budgetSnapshots: BudgetSnapshotSource,
) : PolicyEngine {

    override suspend fun shouldAutoApproveTool(conversationId: Uuid, toolName: String): Boolean {
        val snapshot = approvalSnapshots.snapshot()
        return ToolApprovalPolicy.shouldAutoApprove(
            toolName = toolName,
            blacklist = snapshot.blacklist,
            mode = snapshot.mode,
            headless = HeadlessConversations.shouldAutoApprove(conversationId),
            allowedForChat = ToolApprovalAllowList.isAllowedForChat(conversationId, toolName),
        )
    }

    override suspend fun tokenBudgetSnapshot(conversationId: Uuid?): TokenBudgetTracker.Snapshot? =
        budgetSnapshots.snapshot(conversationId)
}
