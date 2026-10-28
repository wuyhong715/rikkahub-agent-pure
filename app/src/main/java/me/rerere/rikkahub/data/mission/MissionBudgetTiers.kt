package me.rerere.rikkahub.data.mission

/**
 * 批 4 — 任务预算档位。
 *
 * 起因是默认值太低：旧默认 `roundTimeoutMinutes=30 / maxResumeRounds=5 / tokenBudget=500_000`
 * 在真实任务里只够一轮半（一轮 60 分钟、几十次工具调用常烧 15–30 万 token），大部分任务在
 * 真正开始干活之前就被熔断。与其让用户逐个去调四个数字，不如给他三个有名字的档位。
 *
 * 这里只放纯函数：档位 → [MissionLimits] 的映射、反查、以及金额格式化。UI 只负责把档位
 * 显示成 chip、把 `null` 显示成「自定义」。
 *
 * 约定：
 * - 档位只管四个预算字段（单轮分钟数 / 续跑轮数 / token 预算 / 收尾分钟数），
 *   不碰 hooks、验收条件、截止时间、requireConfirmation 等非预算字段。
 * - 所有数值都过 [MissionTimeBudget] 的夹取，档位表写错也不会写出越界值。
 * - [match] 要求四个字段全部相等才算命中；差一个就是用户自定义，UI 不猜。
 */
object MissionBudgetTiers {

    /**
     * 三个预设档位。
     *
     * - [FAST]：快速一枪。适合「查一下 / 整理一份短清单」这类确定能在十分钟内收口的活。
     * - [STANDARD]：默认档。60 分钟一轮 × 12 轮 ≈ 12 小时的有效工作量，300 万 token。
     * - [LONG]：长跑。180 分钟一轮 × 40 轮，收尾窗口 30 分钟，给「跑一整天」的任务用。
     */
    enum class Tier(
        val roundTimeoutMinutes: Int,
        val maxResumeRounds: Int,
        val tokenBudget: Long,
        val wrapUpMinutes: Int,
    ) {
        FAST(roundTimeoutMinutes = 10, maxResumeRounds = 4, tokenBudget = 300_000L, wrapUpMinutes = 2),
        STANDARD(roundTimeoutMinutes = 60, maxResumeRounds = 12, tokenBudget = 3_000_000L, wrapUpMinutes = 10),
        LONG(roundTimeoutMinutes = 180, maxResumeRounds = 40, tokenBudget = 15_000_000L, wrapUpMinutes = 30),
    }

    /** 新建任务时的默认档位。与 [MissionLimits] 的默认值保持一致。 */
    val DEFAULT: Tier = Tier.STANDARD

    /**
     * 把档位的四个预算字段写进 [limits]，其余字段（hooks / 验收 / 截止时间 / 需确认…）原样保留。
     */
    fun apply(limits: MissionLimits, tier: Tier): MissionLimits = limits.copy(
        roundTimeoutMinutes = MissionTimeBudget.clampRoundMinutes(tier.roundTimeoutMinutes),
        maxResumeRounds = tier.maxResumeRounds.coerceAtLeast(0),
        tokenBudget = tier.tokenBudget.coerceAtLeast(0L),
        wrapUpMinutes = MissionTimeBudget.clampWrapUpMinutes(tier.wrapUpMinutes),
    )

    /** 反查：四个预算字段全部与某个档位相等才返回该档位；否则 null（= 自定义）。 */
    fun match(limits: MissionLimits): Tier? = Tier.entries.firstOrNull { tier ->
        tier.roundTimeoutMinutes == limits.roundTimeoutMinutes &&
            tier.maxResumeRounds == limits.maxResumeRounds &&
            tier.tokenBudget == limits.tokenBudget &&
            tier.wrapUpMinutes == limits.wrapUpMinutes
    }

    /** 档位的预算结论（不带本地化措辞，UI 用字符串模板拼装）。 */
    fun summary(limits: MissionLimits): Summary = Summary(
        roundTimeoutMinutes = MissionTimeBudget.clampRoundMinutes(limits.roundTimeoutMinutes),
        maxResumeRounds = limits.maxResumeRounds.coerceAtLeast(0),
        tokenBudget = limits.tokenBudget.coerceAtLeast(0L),
        wrapUpMinutes = MissionTimeBudget.clampWrapUpMinutes(limits.wrapUpMinutes),
    )

    /**
     * 预算数字的显示形式：`3,000,000` → `3,000,000`（带千分位），
     * 小于一万的量级直接给数字，避免出现 `0.0万` 这种没信息量的写法。
     */
    fun formatTokenBudget(tokens: Long): String {
        val safe = tokens.coerceAtLeast(0L)
        return buildString {
            val digits = safe.toString()
            digits.forEachIndexed { index, ch ->
                if (index > 0 && (digits.length - index) % 3 == 0) append(',')
                append(ch)
            }
        }
    }

    /** [summary] 的载体，避免 UI 各自去夹取一遍。 */
    data class Summary(
        val roundTimeoutMinutes: Int,
        val maxResumeRounds: Int,
        val tokenBudget: Long,
        val wrapUpMinutes: Int,
    )
}
