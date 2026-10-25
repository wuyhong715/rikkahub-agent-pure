package me.rerere.rikkahub.data.mission

import kotlinx.serialization.Serializable

/**
 * 批 F — 任务的自定义检查命令（hook）。
 *
 * 任务是无人值守跑的，所以「做完了没有 / 做得对不对」不能只靠模型自报。一个 hook 就是一条
 * 在固定时机执行的 shell 命令：退出码 0 = 通过，非 0 = 不通过。它把「验收」从 prompt 里的
 * 请求变成了系统可执行的门。
 *
 * 三个时机（[MissionHookWhen]）：
 * - [WHEN_ROUND_END]    —— 一轮结束、督导准备续跑之前。失败时可以选择直接停任务，或者只是
 *   把失败输出带进下一轮的交接提示里（让模型自己修）。
 * - [WHEN_BEFORE_DONE]  —— 模型用 `mission_report(done)` 声明完成之后、任务真正关闭之前。
 *   失败 = 不算完成，把检查输出当作返工要求再跑一轮（受 [MissionLimits.maxCheckFailures] 限制）。
 * - [WHEN_AFTER_FAILURE]—— 本轮 run 以失败/超时/进程丢失结束时。用于收集现场（日志、dumpsys），
 *   输出会进入下一轮交接提示。
 *
 * 存储：这个对象序列化进 `missions.limits_json`（[MissionLimits.hooks]），**不新增列、不迁移**。
 * 字段全部带默认值，旧任务解出来的 hooks 是空列表，行为与批 E 完全一致。
 *
 * 命令在工作区的 FILES 根目录下执行（与模型自己的 shell 工具同一个 shell 环境）。支持的占位符
 * 由 [MissionHookSpec.expand] 展开：`{mission}` `{round}` `{run}` `{status}`。
 */
@Serializable
data class MissionHook(
    /** [MissionHookWhen] 的名字（小写），未知值按 [WHEN_ROUND_END] 处理。 */
    @kotlinx.serialization.SerialName("when")
    val at: String = WHEN_ROUND_END,
    /** 要执行的 shell 命令；空白 = 未配置（[isRunnable] 为 false，整套执行路径直接跳过）。 */
    val command: String = "",
    /** 单条命令的超时（秒），[MissionHookSpec.sanitize] 会夹到 5..1800。 */
    val timeoutSec: Int = 120,
    /** 失败时的动作：[ACTION_BLOCK] 阻断，[ACTION_WARN] 只记录并继续。 */
    val failAction: String = ACTION_BLOCK,
    /**
     * 批 I — 工具级 hook 的工具匹配（只有 [WHEN_TOOL_BEFORE] / [WHEN_TOOL_AFTER] 用）。
     *
     * 空 = 匹配所有工具；支持 `workspace_*` 这样的通配（`*` 匹配任意后缀）和精确名。
     * 写成 `write_*` 就能只在写文件之后跑 lint，而不是每次读文件都跑一遍。
     */
    val tool: String = "",
) {

    val whenKind: MissionHookWhen get() = MissionHookWhen.parse(at)

    val action: MissionHookAction get() = MissionHookAction.parse(failAction)

    /** 空白命令视为「没配这个 hook」，执行层据此短路。 */
    val isRunnable: Boolean get() = command.isNotBlank()

    companion object {
        const val WHEN_ROUND_END: String = "round_end"
        const val WHEN_BEFORE_DONE: String = "before_done"
        const val WHEN_AFTER_FAILURE: String = "after_failure"
        const val WHEN_TOOL_BEFORE: String = "tool_before"
        const val WHEN_TOOL_AFTER: String = "tool_after"

        const val ACTION_BLOCK: String = "block"
        const val ACTION_WARN: String = "warn"
    }
}

/** hook 的执行时机。 */
enum class MissionHookWhen {
    ROUND_END,
    BEFORE_DONE,
    AFTER_FAILURE,
    /** 批 I — 工具执行之前（可拦截）。 */
    TOOL_BEFORE,
    /** 批 I — 工具执行之后（只观察，不拦截）。 */
    TOOL_AFTER;

    /** 序列化用的名字（小写），与 [MissionHook.when] 一致。 */
    val wireName: String get() = name.lowercase()

    companion object {
        /** 宽松解析：大小写不敏感，未知值退回 [ROUND_END]（宁可多查一次也不静默丢弃）。 */
        fun parse(raw: String?): MissionHookWhen {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireName == normalized } ?: ROUND_END
        }
    }
}

/** hook 失败时的动作。未知值按 [BLOCK] 处理 —— 配了检查就默认它是硬门。 */
enum class MissionHookAction {
    BLOCK,
    WARN;

    companion object {
        fun parse(raw: String?): MissionHookAction {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.name.lowercase() == normalized } ?: BLOCK
        }
    }
}

/** [MissionHookSpec.expand] 的替换上下文。 */
data class MissionHookContext(
    val missionId: String,
    val round: Int,
    val runId: String? = null,
    /** 本轮 run 的终态名（COMPLETED / FAILED / TIMED_OUT …），未知时 null。 */
    val status: String? = null,
    /** 批 I — 触发这条 hook 的工具名（工具级 hook 用）。 */
    val tool: String? = null,
)

/**
 * hook 的纯规则：挑出某时机的命令、清洗用户输入、展开占位符。
 *
 * 全部是不依赖 Android / Room 的纯函数，直接跑在裸 JVM 单测里（见 `MissionHookSpecTest`）。
 */
object MissionHookSpec {

    /** 一个任务最多带这么多条 hook（每条每个时机最多一条，留出余量）。 */
    const val MAX_HOOKS: Int = 8

    /** 命令字符串的上限，防止把整个脚本塞进 JSON 列。 */
    const val MAX_COMMAND_CHARS: Int = 2_000

    const val MIN_TIMEOUT_SEC: Int = 5
    const val MAX_TIMEOUT_SEC: Int = 1_800

    /** 失败输出进入提示词前的截断长度。 */
    const val MAX_OUTPUT_CHARS: Int = 2_000

    /** 清洗：丢掉空命令、夹住超时、规范化时机/动作名、按上限截断。顺序保持用户给的顺序。 */
    fun sanitize(hooks: List<MissionHook>): List<MissionHook> = hooks
        .asSequence()
        .filter { it.command.isNotBlank() }
        .take(MAX_HOOKS)
        .map { hook ->
            hook.copy(
                at = hook.whenKind.wireName,
                command = hook.command.trim().take(MAX_COMMAND_CHARS),
                timeoutSec = hook.timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC),
                failAction = hook.action.name.lowercase(),
            )
        }
        .toList()

    /** 某时机的全部可执行 hook（通常是 0 或 1 条）。 */
    fun runnable(limits: MissionLimits, at: MissionHookWhen): List<MissionHook> =
        limits.hooks.filter { it.whenKind == at && it.isRunnable }

    /** 把三条命令打包成 limits.hooks（空命令自动丢弃）。 */
    fun of(
        roundEnd: String = "",
        beforeDone: String = "",
        afterFailure: String = "",
        failAction: String = MissionHook.ACTION_BLOCK,
    ): List<MissionHook> = sanitize(
        listOf(
            MissionHook(at = MissionHook.WHEN_ROUND_END, command = roundEnd, failAction = failAction),
            MissionHook(at = MissionHook.WHEN_BEFORE_DONE, command = beforeDone, failAction = failAction),
            MissionHook(at = MissionHook.WHEN_AFTER_FAILURE, command = afterFailure, failAction = failAction),
        )
    )

    /**
     * 批 I — 工具名是否匹配 hook 的 `tool` 字段。空模式匹配一切；`*` 只能出现在末尾。
     */
    fun matchesTool(hook: MissionHook, toolName: String): Boolean {
        val pattern = hook.tool.trim()
        if (pattern.isEmpty()) return true
        if (pattern == "*") return true
        return if (pattern.endsWith("*")) {
            toolName.startsWith(pattern.dropLast(1))
        } else {
            toolName.equals(pattern, ignoreCase = true)
        }
    }

    /** 某时机 + 某工具下要执行的 hook（已清洗，保持用户顺序）。 */
    fun runnableForTool(limits: MissionLimits, at: MissionHookWhen, toolName: String): List<MissionHook> =
        limits.hooks.filter { it.whenKind == at && it.isRunnable && matchesTool(it, toolName) }

    /** 取某时机的第一条命令（UI 里的单行编辑用）；没有则空串。 */
    fun commandFor(limits: MissionLimits, at: MissionHookWhen): String =
        limits.hooks.firstOrNull { it.whenKind == at }?.command.orEmpty()

    /**
     * 批 I — 工具级 hook 的编辑器语法（每行一条）：
     *
     * ```
     * 前: workspace_write_* | ./precheck.sh      # 执行前拦截
     * 后: workspace_* | ./lint.sh                # 执行后观察
     * ```
     *
     * `*` 省略时按「所有工具」处理；命令可以带 `{tool}` 占位符。失败动作沿用任务整体的开关
     * （[actionOf]），所以编辑器不需要再问一次「失败要挡还是只记」。
     */
    fun parseToolHookLines(text: String, failAction: String): List<MissionHook> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val colon = line.indexOfFirst { it == ':' || it == '：' }
                if (colon <= 0) return@mapNotNull null
                val rawWhen = line.substring(0, colon).trim().lowercase()
                val at = when (rawWhen) {
                    "前", "before", "tool_before" -> MissionHook.WHEN_TOOL_BEFORE
                    "后", "after", "tool_after" -> MissionHook.WHEN_TOOL_AFTER
                    else -> return@mapNotNull null
                }
                val rest = line.substring(colon + 1).trim()
                if (rest.isEmpty()) return@mapNotNull null
                val parts = rest.split("|", "｜").map { it.trim() }
                val tool = if (parts.size >= 2) parts[0] else ""
                val command = if (parts.size >= 2) parts.drop(1).joinToString("|").trim() else rest
                if (command.isEmpty()) return@mapNotNull null
                MissionHook(at = at, command = command, tool = tool, failAction = failAction)
            }
            .toList()

    /** 反向：把工具级 hook 渲染回编辑器文本（时间/失败后 hook 不在这里显示）。 */
    fun toToolHookLines(hooks: List<MissionHook>): String =
        hooks.filter { it.whenKind == MissionHookWhen.TOOL_BEFORE || it.whenKind == MissionHookWhen.TOOL_AFTER }
            .joinToString("\n") { hook ->
                val prefix = if (hook.whenKind == MissionHookWhen.TOOL_BEFORE) "前" else "后"
                if (hook.tool.isBlank()) "$prefix: ${hook.command}" else "$prefix: ${hook.tool} | ${hook.command}"
            }

    /** 全部 hook 共用一个失败动作（UI 只有一个开关）；以第一条为准，没有则 BLOCK。 */
    fun actionOf(limits: MissionLimits): MissionHookAction =
        limits.hooks.firstOrNull()?.action ?: MissionHookAction.BLOCK

    /**
     * 展开命令里的占位符。只替换已知键，未知的 `{...}` 保持原样（shell 自己的花括号语法不受影响）。
     */
    fun expand(command: String, ctx: MissionHookContext): String {
        if (command.isEmpty()) return command
        var out = command
        out = out.replace("{mission}", ctx.missionId)
        out = out.replace("{round}", ctx.round.toString())
        out = out.replace("{run}", ctx.runId.orEmpty())
        out = out.replace("{status}", ctx.status.orEmpty())
        out = out.replace("{tool}", ctx.tool.orEmpty())
        return out
    }

    /** 截断长输出：保留尾部（失败原因几乎总在最后），并标注省略了多少字符。 */
    fun clipOutput(raw: String, maxChars: Int = MAX_OUTPUT_CHARS): String {
        val text = raw.trim()
        if (text.length <= maxChars) return text
        val head = "…（省略 ${text.length - maxChars} 字符）\n"
        return head + text.takeLast(maxChars)
    }
}
