package me.rerere.rikkahub.data.mission

import kotlinx.serialization.Serializable

/**
 * 批 H — 结构化验收条件：把「模型说自己做完了」换成「机器判定做完了」。
 *
 * 批 A 的验收条件是纯字符串列表（`missions.acceptance_criteria_json`），只能进提示词、
 * 只能靠模型自评，`mission_report(goals=[...])` 报哪几条全凭它自觉。批 H 在
 * [MissionLimits] 里加一份结构化版本：每条都带一个可执行的判定方式，完成时逐条跑，
 * 全部通过才落 DONE，否则把未通过项当返工要求再跑一轮。
 *
 * 为什么放在 limits_json 而不是新表：与批 F 的 hooks 同一理由——加字段不迁移 schema，
 * 旧任务读出来就是空列表，行为与批 E 完全一致。
 *
 * [kind] 的判定方式刻意保持「能在手机本地判定」这一条底线：
 * - [COMMAND]：在工作区 shell 里跑命令，退出码 0 即通过（`pytest -q`、`./verify.sh`）。
 * - [FILE_EXISTS]：工作区里存在该文件/目录。
 * - [FILE_CONTAINS]：文本文件包含 [target] 子串。
 * - [FILE_NOT_CONTAINS]：文本文件不包含 [target]（用来挡「TODO 没清」「占位符还在」）。
 * - [MIN_BYTES]：文件 >= [value] 字节（挡空文件交差）。
 * - [MANUAL]：机器判不了，只记录、不阻断（人工确认类条件）。
 */
@Serializable
data class AcceptanceCheck(
    /** 给人看的条件描述，同时进提示词让模型知道会被查什么。 */
    val text: String = "",
    val kind: AcceptanceKind = AcceptanceKind.MANUAL,
    /** COMMAND = 命令；FILE_* = 工作区相对路径；FILE_CONTAINS = 要包含的子串。 */
    val target: String = "",
    /** FILE_CONTAINS 的路径（target 放子串，path 放文件）。 */
    val path: String = "",
    /** MIN_BYTES 的阈值。 */
    val value: Long? = null,
    /** COMMAND 的超时秒数。 */
    val timeoutSec: Int = 120,
) {
    /** 是否具备可执行的判定条件；false 的项只会被记录，不参与阻断。 */
    val machineCheckable: Boolean
        get() = when (kind) {
            AcceptanceKind.COMMAND -> target.isNotBlank()
            AcceptanceKind.FILE_EXISTS -> target.isNotBlank()
            AcceptanceKind.FILE_CONTAINS -> path.isNotBlank() && target.isNotBlank()
            AcceptanceKind.FILE_NOT_CONTAINS -> path.isNotBlank() && target.isNotBlank()
            AcceptanceKind.MIN_BYTES -> target.isNotBlank() && (value ?: 0L) > 0L
            AcceptanceKind.MANUAL -> false
        }

    /** 一行展示（时间线 / 返工提示词）。 */
    fun describe(): String = when (kind) {
        AcceptanceKind.COMMAND -> "$text（命令：$target）"
        AcceptanceKind.FILE_EXISTS -> "$text（文件：$target）"
        AcceptanceKind.FILE_CONTAINS -> "$text（$path 应包含「$target」）"
        AcceptanceKind.FILE_NOT_CONTAINS -> "$text（$path 不应包含「$target」）"
        AcceptanceKind.MIN_BYTES -> "$text（$target 至少 ${value ?: 0} 字节）"
        AcceptanceKind.MANUAL -> "$text（人工确认）"
    }
}

/** 验收判定方式。序列化用名字，新增枚举值不影响旧数据。 */
@Serializable
enum class AcceptanceKind {
    COMMAND,
    FILE_EXISTS,
    FILE_CONTAINS,
    FILE_NOT_CONTAINS,
    MIN_BYTES,
    MANUAL,
}

/** 单条验收的判定结果。 */
data class AcceptanceOutcome(
    val check: AcceptanceCheck,
    /** true = 通过；false = 明确不通过；null = 没跑（基础设施故障 / 人工项）。 */
    val passed: Boolean?,
    val detail: String = "",
) {
    val blocking: Boolean get() = passed == false && check.machineCheckable

    fun describe(): String = when (passed) {
        true -> "✓ ${check.text}"
        false -> "✗ ${check.text}${if (detail.isBlank()) "" else " — $detail"}"
        null -> "… ${check.text}${if (detail.isBlank()) "" else " — $detail"}"
    }
}

/** 验收条件的清洗与文本化。 */
object AcceptanceCheckSpec {

    const val MAX_CHECKS: Int = 20
    const val MIN_TIMEOUT_SEC: Int = 5
    const val MAX_TIMEOUT_SEC: Int = 1_800
    const val MAX_TEXT_CHARS: Int = 300

    /** 丢掉没意义的项（空描述且无判定条件、越界数值），并按上限截断。 */
    fun sanitize(checks: List<AcceptanceCheck>): List<AcceptanceCheck> =
        checks.asSequence()
            .mapNotNull { check ->
                val text = check.text.trim().take(MAX_TEXT_CHARS)
                val target = check.target.trim()
                val path = check.path.trim()
                if (text.isEmpty() && target.isEmpty()) return@mapNotNull null
                check.copy(
                    text = text.ifEmpty { target },
                    target = target,
                    path = path,
                    value = check.value?.takeIf { it > 0L },
                    timeoutSec = check.timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC),
                ).takeIf { it.machineCheckable || it.kind == AcceptanceKind.MANUAL }
            }
            .take(MAX_CHECKS)
            .toList()

    /**
     * 编辑器语法：每行一条，`类型: 参数`。
     *
     * 之所以不用结构化表单：验收条件天然是「几条断言」，一行一条比一堆下拉框快得多，
     * 而且用户可以直接从 README 里粘命令。语法刻意做得宽松（中文/英文类型名都认），
     * 解析失败的行不会被静默丢弃，而是保留成 [AcceptanceKind.MANUAL]——写错了至少看得见。
     *
     * - `命令: pytest -q`
     * - `文件: report.md`
     * - `包含: report.md | 结论`   （path | 子串）
     * - `不含: report.md | TODO`
     * - `大小: report.md | 1024`
     * - 其它任意文本 → 人工确认项
     */
    fun parseEditorLines(text: String): List<AcceptanceCheck> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line -> parseLine(line) }
            .toList()

    private fun parseLine(line: String): AcceptanceCheck? {
        val colon = line.indexOfFirst { it == ':' || it == '：' }
        if (colon <= 0) {
            return AcceptanceCheck(text = line.take(MAX_TEXT_CHARS), kind = AcceptanceKind.MANUAL)
        }
        val rawType = line.substring(0, colon).trim().lowercase()
        val arg = line.substring(colon + 1).trim()
        if (arg.isEmpty()) return null
        return when (rawType) {
            "命令", "command", "cmd" -> AcceptanceCheck(
                text = arg,
                kind = AcceptanceKind.COMMAND,
                target = arg,
            )
            "文件", "file", "exists" -> AcceptanceCheck(
                text = arg,
                kind = AcceptanceKind.FILE_EXISTS,
                target = arg,
            )
            "包含", "contains" -> splitPair(arg)?.let { (path, needle) ->
                AcceptanceCheck(text = "包含「$needle」", kind = AcceptanceKind.FILE_CONTAINS, path = path, target = needle)
            }
            "不含", "not_contains", "notcontains" -> splitPair(arg)?.let { (path, needle) ->
                AcceptanceCheck(text = "不包含「$needle」", kind = AcceptanceKind.FILE_NOT_CONTAINS, path = path, target = needle)
            }
            "大小", "size", "min_bytes" -> splitPair(arg)?.let { (path, size) ->
                AcceptanceCheck(
                    text = "至少 $size 字节",
                    kind = AcceptanceKind.MIN_BYTES,
                    target = path,
                    value = size.toLongOrNull(),
                )
            }
            else -> AcceptanceCheck(text = line.take(MAX_TEXT_CHARS), kind = AcceptanceKind.MANUAL)
        }
    }

    private fun splitPair(raw: String): Pair<String, String>? {
        val parts = raw.split("|", "｜").map { it.trim() }
        if (parts.size < 2) return null
        val left = parts[0]
        val right = parts.drop(1).joinToString("|").trim()
        if (left.isEmpty() || right.isEmpty()) return null
        return left to right
    }

    /** 反向：把已存的检查渲染回编辑器文本（保留用户能看懂的写法）。 */
    fun toEditorLines(checks: List<AcceptanceCheck>): String =
        checks.joinToString("\n") { check ->
            when (check.kind) {
                AcceptanceKind.COMMAND -> "命令: ${check.target}"
                AcceptanceKind.FILE_EXISTS -> "文件: ${check.target}"
                AcceptanceKind.FILE_CONTAINS -> "包含: ${check.path} | ${check.target}"
                AcceptanceKind.FILE_NOT_CONTAINS -> "不含: ${check.path} | ${check.target}"
                AcceptanceKind.MIN_BYTES -> "大小: ${check.target} | ${check.value ?: 0}"
                AcceptanceKind.MANUAL -> check.text
            }
        }

    /** 给提示词用：告诉模型完成时会被逐条检查什么。 */
    fun composePromptBlock(checks: List<AcceptanceCheck>): String {
        val runnable = checks.filter { it.text.isNotBlank() }
        if (runnable.isEmpty()) return ""
        return buildString {
            appendLine("[验收条件] 任务完成时会被逐条机器判定，全部通过才算完成：")
            runnable.forEachIndexed { index, check -> appendLine("${index + 1}. ${check.describe()}") }
        }.trimEnd()
    }
}
