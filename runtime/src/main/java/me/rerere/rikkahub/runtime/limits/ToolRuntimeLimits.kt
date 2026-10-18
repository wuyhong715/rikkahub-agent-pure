package me.rerere.rikkahub.runtime.limits

/**
 * App-wide @Volatile runtime holder for tool execution limits that span all tool families
 * (not just Termux). Currently holds the per-turn wall-clock budget that was previously
 * hardcoded in GenerationHandler.kt.
 *
 * Pushed from [me.rerere.rikkahub.data.preferences.TermuxPreferences.init] because the
 * Termux settings page is where the user configures it (per spec: "exposed on the Termux
 * page since that's where issue #5 raised it"). The field itself is app-wide and the
 * holder lives in the :runtime module to reflect that.
 *
 * Default values are inlined here (mirroring [me.rerere.rikkahub.data.preferences.TermuxDefaults])
 * so :runtime does not depend on :app. Keep them in sync with TermuxDefaults if they change.
 */
object ToolRuntimeLimits {
    @Volatile var turnBudgetMs: Long = 30L * 60L * 1_000L   // 30 min — mirrors TermuxDefaults.DEFAULT_TURN_BUDGET_MS

    /**
     * Tool-call iterations a single turn may run before it is force-ended. Was hardcoded at 32,
     * which truncated long Termux chains mid-task (issue #22).
     */
    @Volatile var maxToolSteps: Int = 90   // mirrors TermuxDefaults.DEFAULT_MAX_TOOL_STEPS
}
