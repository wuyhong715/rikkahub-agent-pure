package me.rerere.rikkahub.service.mission

import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionHook
import me.rerere.rikkahub.data.mission.MissionHookContext
import me.rerere.rikkahub.data.mission.MissionHookSpec
import me.rerere.rikkahub.data.mission.workspaceIdOrNull

/**
 * 批 F — 检查命令（hook）的执行层。
 *
 * 一条命令从「用户写在设置里」到「拿到退出码」只经过这里：展开占位符 → 在任务工作区的 shell 里跑
 * → 截断输出。执行环境与模型自己的 shell 工具完全一致（同一个 workspace、同一个 proot/宿主 shell），
 * 所以用户写的 `pytest -q` 或 `./verify.sh` 在两边行为相同。
 *
 * 失败语义由 [MissionHookOutcome] 承担，判断逻辑刻意放在这里而不是 supervisor 里，方便单测：
 * - 命令跑了且退出码非 0 → [MissionHookOutcome.failed]；
 * - `block` 的 hook 失败 → [MissionHookOutcome.blocking]，任务停下；
 * - `warn` 的 hook 失败，或命令根本跑不起来（没工作区 / shell 崩了）→ 只记录，不阻断任务。
 *
 * 最后一条是刻意的：检查基础设施坏掉，不等于被检查的工作坏掉。与 token 计量故障不停健康任务同一原则。
 */
class MissionHookExecutor(
    private val shell: MissionHookShell,
) {

    /** 跑一条 hook；未配置（空白命令）返回 null，调用方据此完全跳过。 */
    suspend fun run(
        mission: MissionEntity,
        hook: MissionHook,
        round: Int,
        runId: String? = null,
        status: String? = null,
    ): MissionHookOutcome? {
        if (!hook.isRunnable) return null
        val workspaceId = mission.workspaceIdOrNull ?: return MissionHookOutcome(
            hook = hook,
            result = null,
            skippedReason = "任务没有工作区，检查未执行",
        )
        val command = MissionHookSpec.expand(
            hook.command,
            MissionHookContext(missionId = mission.id, round = round, runId = runId, status = status),
        )
        val startedAt = System.currentTimeMillis()
        val result = runCatching {
            shell.execute(
                workspaceId = workspaceId,
                command = command,
                timeoutMs = hook.timeoutSec.coerceIn(
                    MissionHookSpec.MIN_TIMEOUT_SEC,
                    MissionHookSpec.MAX_TIMEOUT_SEC,
                ) * 1_000L,
            )
        }.getOrElse { error ->
            HookResult(error = error.message ?: error::class.simpleName ?: "unknown")
        }
        return MissionHookOutcome(
            hook = hook,
            result = result,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }
}

/** 一次 shell 执行的结果（与 workspace 模块的 `WorkspaceCommandResult` 解耦，便于测试）。 */
data class HookResult(
    val exitCode: Int = -1,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    /** 命令根本没跑起来时的原因（没工作区、进程创建失败……）。非 null 即视为「未得到结论」。 */
    val error: String? = null,
) {
    /** 只有真的跑完且退出码为 0 才算通过。 */
    val ok: Boolean get() = error == null && !timedOut && exitCode == 0

    /** 给提示词/通知用的简短描述：失败时优先 stderr（测试框架的失败摘要通常在那里）。 */
    fun summary(maxChars: Int = MissionHookSpec.MAX_OUTPUT_CHARS): String {
        if (error != null) return "未执行：$error"
        if (timedOut) return "超时未完成"
        val body = stderr.trim().ifEmpty { stdout.trim() }
        if (body.isEmpty()) return "exit=$exitCode（无输出）"
        return "exit=$exitCode\n" + MissionHookSpec.clipOutput(body, maxChars)
    }
}

/** 一条 hook 跑完后的结论。 */
data class MissionHookOutcome(
    val hook: MissionHook,
    /** null = 没跑（没工作区）。 */
    val result: HookResult?,
    val durationMs: Long = 0,
    val skippedReason: String? = null,
) {
    /**
     * 拿到了明确的失败结论 —— **执行基础设施故障不算**。
     *
     * `error != null` 表示命令根本没跑起来（没有工作区、shell 未就绪、进程创建失败、瞬时超时）。
     * 那是环境的结论，不是对工作的结论：把它当成校验失败，任何一次 shell 抖动都会阻断
     * ROUND_END / BEFORE_DONE，任务就此卡住 —— 正好违背督导那侧写明的软失败约定
     * （基础设施异常记日志放行，只有真的跑完且非 0 退出才算不通过）。
     */
    val failed: Boolean get() = result != null && !result.ok && result.error == null

    /** 是否应该阻断任务：只有 `block` 的 hook 拿到明确失败结论时才阻断。 */
    val blocking: Boolean get() = failed && hook.action.name.equals("BLOCK", ignoreCase = true)

    /** 一行摘要（通知 / journal detail 用）。 */
    fun describe(): String {
        val label = when (hook.whenKind.name) {
            "ROUND_END" -> "轮末检查"
            "BEFORE_DONE" -> "完成前检查"
            "AFTER_FAILURE" -> "失败后检查"
            else -> "检查"
        }
        return when {
            skippedReason != null -> "$label：$skippedReason"
            result == null -> "$label：未执行"
            result.ok -> "$label：通过"
            else -> "$label：不通过\n${result.summary()}"
        }
    }
}

/**
 * 真正去 shell 里跑命令的出口。DI 接 `WorkspaceRepository.executeCommand`，测试直接给假实现。
 *
 * 约定：实现不抛异常；抛了也会被 [MissionHookExecutor] 兜成 `HookResult.error`。
 */
fun interface MissionHookShell {
    suspend fun execute(workspaceId: String, command: String, timeoutMs: Long): HookResult
}
