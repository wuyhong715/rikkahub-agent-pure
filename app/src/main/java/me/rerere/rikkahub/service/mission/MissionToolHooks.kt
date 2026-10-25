package me.rerere.rikkahub.service.mission

import android.util.Log
import me.rerere.rikkahub.data.mission.MissionHook
import me.rerere.rikkahub.data.mission.MissionHookContext
import me.rerere.rikkahub.data.mission.MissionHookSpec
import me.rerere.rikkahub.data.mission.MissionHookWhen
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.limits
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType

private const val TAG = "MissionToolHooks"

/**
 * 批 I — 工具级 hook（PreToolUse / PostToolUse）。
 *
 * 批 F 的 hook 只有「轮末 / 完成前 / 失败后」三个时机，粒度太粗：想做「写完文件就跑 lint」
 * 或者「跑 shell 之前先拦一条危险命令」，只能等一轮结束才发生——那时错误已经扩散了。
 *
 * 这里补两个时机，作用域仍然**只在任务会话**（[MissionSessions] 里登记的会话）：
 * - [MissionHookWhen.TOOL_BEFORE]：工具执行前跑。`block` 的失败会**拦截这次调用**（工具不会执行，
 *   模型收到一条错误说明）；`warn` 的失败只记录。这是「策略前置」的位置——例如禁止 `git push`、
 *   禁止 `curl` 外发数据。
 * - [MissionHookWhen.TOOL_AFTER]：工具执行后跑。只观察、不拦截（工具已经执行完了，拦没有意义）；
 *   失败记进 run journal，供下一轮交接与运行报告统计（例如「每次写完 lint 都报错」）。
 *
 * 为什么放在 [MissionEngine] 之外：engine 不该依赖 shell（它要能在纯 JVM 里测），
 * 而 hook 的执行环境必须与模型自己的 shell 工具一致。分开之后两边都能单测。
 */
class MissionToolHooks(
    private val missionRepository: MissionRepository,
    private val runEventRepository: RunEventRepository,
    private val executor: MissionHookExecutor,
) {

    /**
     * 执行前置 hook；返回非 null 表示**拦截**，内容是给模型看的理由。
     *
     * 失败语义与批 F 一致：命令跑不起来（没工作区 / shell 崩了）一律放行——检查基础设施坏了，
     * 不等于这次工具调用有问题。
     */
    suspend fun before(conversationId: String, toolName: String, argsJson: String): String? {
        val mission = missionFor(conversationId) ?: return null
        val hooks = MissionHookSpec.runnableForTool(mission.limits, MissionHookWhen.TOOL_BEFORE, toolName)
        if (hooks.isEmpty()) return null
        for (hook in hooks) {
            val outcome = executor.run(
                mission = mission,
                hook = hook,
                round = mission.resumeCount + 1,
                runId = mission.lastRunId,
                status = toolName,
            ) ?: continue
            if (outcome.skippedReason != null) {
                journal(mission.id, conversationId, mission.lastRunId, "tool_hook_skipped", outcome.describe())
                continue
            }
            if (!outcome.failed) continue
            journal(
                mission.id,
                conversationId,
                mission.lastRunId,
                if (outcome.blocking) "tool_hook_blocked" else "tool_hook_warn",
                "${toolName}：${outcome.describe()}",
            )
            if (outcome.blocking) {
                return "被任务的前置检查拦截（$toolName）。检查命令：${hook.command}\n${outcome.result?.summary().orEmpty()}"
            }
        }
        return null
    }

    /**
     * 执行后置 hook。刻意不返回结果：工具已经执行完，这里的失败只用于记录与统计。
     *
     * @param outputJson 工具输出（截断后），给 hook 当参考；不参与命令展开。
     */
    suspend fun after(conversationId: String, toolName: String, outputJson: String) {
        val mission = missionFor(conversationId) ?: return
        val hooks = MissionHookSpec.runnableForTool(mission.limits, MissionHookWhen.TOOL_AFTER, toolName)
        if (hooks.isEmpty()) return
        for (hook in hooks) {
            val outcome = executor.run(
                mission = mission,
                hook = hook,
                round = mission.resumeCount + 1,
                runId = mission.lastRunId,
                status = toolName,
            ) ?: continue
            when {
                outcome.skippedReason != null -> journal(
                    mission.id,
                    conversationId,
                    mission.lastRunId,
                    "tool_hook_skipped",
                    outcome.describe(),
                )
                outcome.failed -> journal(
                    mission.id,
                    conversationId,
                    mission.lastRunId,
                    "tool_hook_failed",
                    "$toolName：${outcome.describe()}",
                )
                else -> Unit // 通过不记流水，避免十万条「通过」
            }
        }
    }

    private suspend fun missionFor(conversationId: String) = runCatching {
        val missionId = MissionSessions.missionIdFor(conversationId)
            ?: missionRepository.findActiveByConversation(conversationId)?.id
            ?: return@runCatching null
        missionRepository.getById(missionId)
    }.getOrNull()

    private suspend fun journal(
        missionId: String,
        conversationId: String,
        runId: String?,
        title: String,
        detail: String,
    ) {
        runCatching {
            runEventRepository.emit(
                type = RunEventType.NOTE,
                title = title,
                detail = detail.take(500),
                missionId = missionId,
                conversationId = conversationId,
                runId = runId,
            )
        }.onFailure { Log.w(TAG, "tool hook journal failed ($title)", it) }
    }
}
