package me.rerere.rikkahub.data.mission

/**
 * 批 H — 任务的时间维度：单轮时长、截止时间、收尾窗口。
 *
 * 全部是纯函数，不读时钟也不碰 IO：调用方把 `now` 传进来，测试可以直接钉住时间点。
 * 放在 data 层而不是 launcher 里，是因为三个地方要共用同一套判断——
 * `MissionLauncher`（本轮怎么跑）、`MissionSupervisor`（还该不该续跑）、UI（还剩多久）。
 * 三处各写一遍阈值，迟早在边界上打架。
 *
 * 语义约定：
 * - **没有截止时间**：每轮按 [MissionLimits.roundTimeoutMinutes] 跑，收尾窗口不生效。
 * - **有截止时间**：本轮超时 = min(单轮上限, 剩余时间)；剩余 <= 0 时拒绝启动（expired），
 *   由督导把任务 park 给用户，而不是让模型在「已经超时」的状态下继续烧钱。
 * - **收尾轮**：剩余时间 <= wrapUpMinutes 且 > 0。本轮提示词会被追加收尾指令，
 *   要求停止开新战线、把已有结果整理成交付物并报告。
 */
object MissionTimeBudget {

    /** 单轮上限默认 30 分钟（与批 A 的常量一致，避免行为突变）。 */
    const val DEFAULT_ROUND_MIN: Int = 30

    /** 单轮上限下限：低于 5 分钟，模型还没热身就被掐。 */
    const val MIN_ROUND_MIN: Int = 5

    /** 单轮上限上限：再长就该靠检查点续跑，而不是一轮硬跑。 */
    const val MAX_ROUND_MIN: Int = 180

    /** 收尾窗口默认 5 分钟。 */
    const val DEFAULT_WRAPUP_MIN: Int = 5

    /** 收尾窗口上限 60 分钟（再长就不是「收尾」了）。 */
    const val MAX_WRAPUP_MIN: Int = 60

    /** 一次启动的时间计划。 */
    data class Plan(
        /** 本轮 run 的墙钟上限。 */
        val timeoutMs: Long,
        /** 是否进入收尾轮（提示词要追加收尾指令）。 */
        val wrapUp: Boolean,
        /** 截止时间已过（或刚好到点），本轮不应启动。 */
        val expired: Boolean,
        /** 距离截止还剩多少毫秒；没有截止时间时为 null。 */
        val remainingMs: Long?,
    )

    fun clampRoundMinutes(minutes: Int): Int = minutes.coerceIn(MIN_ROUND_MIN, MAX_ROUND_MIN)

    fun clampWrapUpMinutes(minutes: Int): Int = minutes.coerceIn(0, MAX_WRAPUP_MIN)

    /**
     * 算出本轮怎么跑。
     *
     * @param now 当前时间（epoch millis），由调用方注入。
     * @param defaultTimeoutMs 兜底的单轮上限（配置缺省或非法时使用）。
     */
    fun plan(
        limits: MissionLimits,
        now: Long,
        defaultTimeoutMs: Long = DEFAULT_ROUND_MIN * 60_000L,
    ): Plan {
        val roundMs = clampRoundMinutes(limits.roundTimeoutMinutes) * 60_000L
        val deadline = limits.deadlineAt
            ?: return Plan(
                timeoutMs = roundMs,
                wrapUp = false,
                expired = false,
                remainingMs = null,
            )
        val remaining = deadline - now
        if (remaining <= 0L) {
            return Plan(timeoutMs = 0L, wrapUp = false, expired = true, remainingMs = remaining)
        }
        val wrapUpWindow = clampWrapUpMinutes(limits.wrapUpMinutes) * 60_000L
        return Plan(
            timeoutMs = minOf(roundMs, remaining).coerceAtLeast(1L),
            wrapUp = wrapUpWindow > 0L && remaining <= wrapUpWindow,
            expired = false,
            remainingMs = remaining,
        )
    }

    /** 截止时间已过时给用户看的说明（督导 park 任务、通知标题用）。 */
    fun expiredReason(limits: MissionLimits): String =
        if (limits.deadlineAt == null) "已到轮次上限" else "已到任务截止时间"

    /**
     * 收尾轮追加到提示词末尾的指令。刻意写得短：这时模型该做的是收口，不是继续规划。
     *
     * @param remainingMs 剩余时间；调用方保证 > 0。
     */
    fun wrapUpDirective(remainingMs: Long): String {
        val minutes = (remainingMs / 60_000L).coerceAtLeast(1L)
        return """
            [系统] 距离任务截止还剩约 $minutes 分钟，本轮是收尾轮。
            收尾轮只做三件事：
            1. 停止开新战线，不要启动新的调研/重构/实验；
            2. 把已经完成的部分整理成交付物（更新工作区里的产出文件与 task-state.md，写清已完成、未完成、下一步）；
            3. 调用 mission_report 结束本轮：做完了报 done，做不完报 blocked 并说明卡在哪。
            没做完的部分留给下一轮或用户，不要为了「看起来完整」而编造结果。
        """.trimIndent()
    }

    /** 格式化剩余时间（UI 与通知共用）：`2h 05m` / `12m` / `<1m`。 */
    fun formatRemaining(remainingMs: Long): String {
        if (remainingMs <= 0L) return "已到期"
        val minutes = remainingMs / 60_000L
        if (minutes <= 0L) return "<1m"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) "${hours}h ${rest.toString().padStart(2, '0')}m" else "${minutes}m"
    }
}
