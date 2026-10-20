package me.rerere.rikkahub.service.mission

/**
 * B1 (批 A · Mission Runtime) — prompt assembly for the mission runtime, as pure functions.
 *
 * Three products, all Chinese, all plain strings:
 * - [MissionContinueComposer.composeDirective]: the standing mission rules
 *   (MISSION_DIRECTIVE) that the launcher injects when a mission starts / a mission
 *   conversation is created.
 * - [MissionContinueComposer.compose]: the handoff prompt the supervisor injects when it
 *   auto-continues a mission whose previous round stopped before completion.
 * - [MissionContinueComposer.composeRework]: the prompt for a user-rejected delivery
 *   (批 D review gate), carrying the change request back to the model.
 *
 * No Android / Room dependencies — everything here is string building.
 */
data class HandoffContext(
    val title: String,
    /** The mission's stated goal (main objective). */
    val goal: String,
    /** Goal-breakdown items that are still open; empty when the mission has no breakdown. */
    val goalsChecked: List<String>,
    /** Produced artifact paths. */
    val artifacts: List<String>,
    /** Tail of the previous round's output; null/blank omits the section. */
    val lastRunTail: String?,
    /** 1-based number of the round being composed for. */
    val round: Int,
    val maxRounds: Int,
)

object MissionContinueComposer {

    /** At most this many artifacts are listed; the rest collapse into `"…等 N 件"` (N = total count). */
    const val MAX_ARTIFACTS: Int = 20

    /** The previous round's tail is capped at this many characters. */
    const val MAX_TAIL_CHARS: Int = 800

    /**
     * The standing mission directive, injected by the launcher (MISSION_DIRECTIVE).
     *
     * States: the agent is in mission mode; the mission title + goal + breakdown; outputs
     * go into the workspace; declare completion via `mission_report(done)` with a delivery
     * summary; declare a blockage via `mission_report(blocked)` with the reason; the system
     * will nudge when a round stops with the mission unfinished.
     */
    fun composeDirective(title: String, goal: String, goals: List<String>): String = buildString {
        appendLine("[任务模式] 你现在处于任务模式：请围绕下方任务自主推进，直到完成或确需用户介入。")
        appendLine()
        appendLine("任务：$title")
        appendLine("目标：$goal")
        if (goals.isNotEmpty()) {
            appendLine()
            appendLine("目标分解：")
            for (item in goals) appendLine("- $item")
        }
        appendLine()
        appendLine("要求：")
        appendLine("- 产出请放进工作区：先落盘再继续，不要只在对话里描述结果。")
        appendLine("- 任务完成时，调用 mission_report(done) 声明完成，并附交付摘要（做了什么、产物在哪）。")
        appendLine("- 确实被卡住（需要用户决策 / 权限 / 信息）时，调用 mission_report(blocked) 声明并说明原因。")
        appendLine("- 如果你停下但任务尚未完成，系统会提醒你继续；请不要在未完成时假称完成。")
    }.trimEnd()

    /**
     * Compose the continuation handoff for a mission whose previous round stopped unfinished.
     *
     * Layout (Chinese, section order fixed):
     * 1. `[任务督导]` opener — the previous round stopped with the mission unfinished, keep going;
     * 2. mission title + goal;
     * 3. still-open goal-breakdown items (section omitted when the list is empty);
     * 4. produced artifacts — first [MAX_ARTIFACTS] listed, the remainder collapsed into
     *    `"…等 N 件"` where N is the total artifact count; `"（暂无）"` when there are none;
     * 5. the previous round's tail, trimmed and capped at [MAX_TAIL_CHARS] characters
     *    (section omitted when null/blank);
     * 6. the round line `"这是第 round/maxRounds 轮"`;
     * 7. closing rules: don't redo finished work; declare done/blocked via `mission_report`.
     */
    fun compose(ctx: HandoffContext): String = buildString {
        appendLine("[任务督导] 上一轮未完成，请继续推进本任务。")
        appendLine()
        appendLine("任务：${ctx.title}")
        appendLine("目标：${ctx.goal}")
        if (ctx.goalsChecked.isNotEmpty()) {
            appendLine()
            appendLine("目标分解（未完成项）：")
            for (item in ctx.goalsChecked) appendLine("- $item")
        }
        appendLine()
        if (ctx.artifacts.isEmpty()) {
            appendLine("已产出清单：（暂无）")
        } else {
            appendLine("已产出清单：")
            for (artifact in ctx.artifacts.take(MAX_ARTIFACTS)) appendLine("- $artifact")
            if (ctx.artifacts.size > MAX_ARTIFACTS) {
                appendLine("…等 ${ctx.artifacts.size} 件")
            }
        }
        val tail = ctx.lastRunTail?.trim()?.takeIf { it.isNotEmpty() }
        if (tail != null) {
            appendLine()
            appendLine("上一轮结尾：")
            appendLine(tail.take(MAX_TAIL_CHARS))
        }
        appendLine()
        appendLine("这是第 ${ctx.round}/${ctx.maxRounds} 轮。")
        appendLine()
        appendLine("请继续推进，不要重做已完成的部分；任务完成时用 mission_report(done) 声明完成，确实被卡住时用 mission_report(blocked) 说明原因。")
    }.trimEnd()

    /**
     * 批 D — prompt for a rework round after the user rejects a delivery in REVIEW.
     *
     * Blank [feedback] is allowed: the round then self-checks against the mission goals and
     * acceptance criteria instead of following specific notes. The closing line keeps the
     * signal contract (`mission_report(done)`) so the reworked delivery goes through the same
     * confirmation gate again.
     */
    fun composeRework(feedback: String): String = buildString {
        appendLine("[验收打回] 用户检查了上一轮的交付，认为还需要修改。")
        val notes = feedback.trim()
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("修改意见：")
            appendLine(notes)
        } else {
            appendLine()
            appendLine("修改意见：未填写，请对照任务目标与验收标准自查并补齐不足。")
        }
        appendLine()
        appendLine("请据此重新执行并交付，不要重做已经符合要求的部分；完成后再次调用 mission_report(done)。")
    }.trimEnd()
}
