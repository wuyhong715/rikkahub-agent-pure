package me.rerere.rikkahub.service.mission

import me.rerere.rikkahub.data.mission.AcceptanceCheck
import me.rerere.rikkahub.data.mission.AcceptanceCheckSpec
import me.rerere.rikkahub.data.mission.AcceptanceKind
import me.rerere.rikkahub.data.mission.AcceptanceOutcome
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.limits
import me.rerere.rikkahub.data.mission.workspaceIdOrNull

/**
 * 批 H — 结构化验收的执行层。
 *
 * 所有判定都走任务工作区的 shell（与 [MissionHookExecutor] 同一个 [MissionHookShell] 出口），
 * 不另开一套文件 API。这样验收看到的世界和模型看到的世界是同一个：
 * 模型用 `workspace_*` 工具写进 rootfs 的文件，`test -e` 也认得；用户在工作区里跑 `pytest`，
 * 验收跑同一条命令得到的也是一样的结果。少一层抽象，就少一处「工具说写好了、验收说没有」。
 *
 * 语义与批 F 的检查命令一致：
 * - 判定跑不起来（没有工作区 / shell 崩了）→ [AcceptanceOutcome.passed] = null，不阻断；
 *   基础设施故障不等于工作不合格。
 * - [AcceptanceKind.MANUAL] 只记录，不阻断。
 * - 只有拿到明确的不通过（退出码非 0、文件不存在、子串缺失）才算 blocking。
 */
class MissionAcceptanceVerifier(
    private val shell: MissionHookShell,
) {

    /** 逐条跑验收；任何一条跑不起来都只影响它自己，不会中断整批。 */
    suspend fun verify(
        mission: MissionEntity,
        runId: String? = null,
    ): List<AcceptanceOutcome> {
        val checks: List<AcceptanceCheck> = mission.limits.acceptanceChecks
        if (checks.isEmpty()) return emptyList()
        val workspaceId = mission.workspaceIdOrNull
            ?: return checks.map { AcceptanceOutcome(it, passed = null, detail = "任务没有工作区，验收未执行") }
        val results = mutableListOf<AcceptanceOutcome>()
        checks.forEach { check -> results.add(runOne(workspaceId, check)) }
        return results
    }

    private suspend fun runOne(workspaceId: String, check: AcceptanceCheck): AcceptanceOutcome {
        if (!check.machineCheckable) {
            return AcceptanceOutcome(check, passed = null, detail = "人工确认项")
        }
        val command = commandFor(check) ?: return AcceptanceOutcome(check, passed = null, detail = "条件不完整")
        val timeoutMs = check.timeoutSec.coerceIn(
            AcceptanceCheckSpec.MIN_TIMEOUT_SEC,
            AcceptanceCheckSpec.MAX_TIMEOUT_SEC,
        ) * 1_000L
        val result = runCatching { shell.execute(workspaceId, command, timeoutMs) }
            .getOrElse { error ->
                HookResult(error = error.message ?: error::class.simpleName ?: "unknown")
            }
        if (result.error != null) {
            return AcceptanceOutcome(check, passed = null, detail = "未执行：${result.error}")
        }
        if (result.timedOut) {
            return AcceptanceOutcome(check, passed = false, detail = "判定超时未完成")
        }
        return when (check.kind) {
            AcceptanceKind.MIN_BYTES -> {
                val actual = result.stdout.trim().toLongOrNull()
                if (actual == null) {
                    AcceptanceOutcome(check, passed = null, detail = "无法读取文件大小")
                } else {
                    val need = check.value ?: 0L
                    AcceptanceOutcome(
                        check,
                        passed = actual >= need,
                        detail = if (actual >= need) "" else "实际 ${actual} 字节",
                    )
                }
            }

            else -> AcceptanceOutcome(
                check,
                passed = result.exitCode == 0,
                detail = if (result.exitCode == 0) "" else result.summary(MAX_DETAIL_CHARS),
            )
        }
    }

    /**
     * 把一条验收翻译成 shell 命令。路径与子串都做单引号转义——验收命令里出现空格、引号、
     * `$` 是常态（比如 `grep 'TODO:'`），拼错一个字符就会得到假的通过/失败。
     */
    private fun commandFor(check: AcceptanceCheck): String? {
        val target = check.target
        return when (check.kind) {
            AcceptanceKind.COMMAND -> target
            AcceptanceKind.FILE_EXISTS -> "test -e ${shellQuote(target)}"
            AcceptanceKind.FILE_CONTAINS ->
                "grep -qF -- ${shellQuote(target)} ${shellQuote(check.path)}"
            AcceptanceKind.FILE_NOT_CONTAINS ->
                "! grep -qF -- ${shellQuote(target)} ${shellQuote(check.path)}"
            AcceptanceKind.MIN_BYTES -> "wc -c < ${shellQuote(target)}"
            AcceptanceKind.MANUAL -> null
        }
    }

    companion object {
        private const val MAX_DETAIL_CHARS = 400

        /** POSIX 单引号转义：把 `'` 变成 `'\''`。 */
        fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
    }
}
