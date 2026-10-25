package me.rerere.rikkahub.service.mission

import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.limits
import me.rerere.rikkahub.data.mission.workspaceIdOrNull
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType

private const val FORK_TITLE_SUFFIX = " · 分支"

/**
 * 批 I — 任务分叉（fork）。
 *
 * 用途：一个任务跑到某个阶段，用户想「换一条路试试」——不是返工，而是保留现在的全部产物，
 * 另起一个任务去试另一个方案。这与「续跑」是两件事：续跑是同一目标继续，分叉是**同一份现状、
 * 不同方向**。对照 Codex 的 `/fork`（worktree 分支），Moxw 的等价物是「新工作区 + 独立任务」。
 *
 * 交付内容：
 * 1. 新建工作区并**拷贝当前 FILES 区**（[MissionForkCopy]）；
 * 2. 用 [MissionCreationService] 建任务并直接启动第一轮——这样分叉出来的任务和手动创建的任务
 *    共享同一条创建路径（工作流文档、默认工作区、模型发起的任务都不例外）；
 * 3. 继承原任务的 runtime 限制、审批姿态、结构化验收与 hook，但**清掉截止时间**：
 *    分叉是一个新任务，不该继承「十分钟后到期」这种属于原任务的时间约束；
 * 4. 两边各留一条 journal（`fork`），让时间线上能看出血缘。
 *
 * 不复制：会话记录（新任务从零开始对话，否则模型会以为之前的工作是它做的）、
 * rootfs（工具链共用，见 [MissionForkCopy] 的注释）、轮次快照（快照属于原任务的回退链）。
 */
class MissionForkService(
    private val missionRepository: MissionRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val creationService: MissionCreationService,
    private val runEventRepository: RunEventRepository,
) {

    data class Result(
        val missionId: String,
        val workspaceId: String,
        val fileCount: Int,
        val started: Boolean,
        /** 非空表示分叉本身成功、但第一轮没起来（任务保留，用户可手动启动）。 */
        val launchError: String? = null,
    )

    /**
     * 分叉 [missionId] 到新任务；[instruction] 是「另一条路」的具体要求，会写进新任务的目标里，
     * 从而每轮都被注入（不依赖一次性提示）。
     */
    suspend fun fork(missionId: String, instruction: String = ""): Result {
        val source = missionRepository.getById(missionId)
            ?: error("任务不存在：$missionId")
        val sourceWorkspace = source.workspaceIdOrNull
            ?: error("任务没有工作区，无法分叉（先在任务里产生一些产物再试）")

        val sourceDir = workspaceRepository.filesDir(sourceWorkspace)
            ?: error("读不到源工作区目录")
        val workspace = createWorkspace(source.title)
        val targetDir = workspaceRepository.filesDir(workspace.id)
            ?: error("读不到新工作区目录")
        val stats = MissionForkCopy.copyTree(sourceDir, targetDir)

        val goal = buildString {
            appendLine(source.description)
            appendLine()
            appendLine("[分支] 本任务是从任务「${source.title}」分叉出来的。")
            appendLine("工作区里是分叉那一刻的产物副本，可以直接在此基础上继续。")
            if (instruction.isNotBlank()) {
                appendLine("走另一条路，要求：")
                appendLine(instruction.trim())
            }
        }.trim()

        val creation = creationService.createAndStart(
            MissionCreationService.Request(
                title = "${source.title}$FORK_TITLE_SUFFIX",
                goal = goal,
                workspaceId = workspace.id,
                assistantId = source.assistantId,
                startNow = true,
            )
        )

        // 继承运行时配置：限制（去掉 deadline）、审批姿态、结构化验收在 limits 里随之带过去。
        runCatching {
            missionRepository.updateLimits(creation.mission.id, source.limits.copy(deadlineAt = null))
            missionRepository.updateApprovalMode(creation.mission.id, source.approvalMode)
        }
        runCatching {
            runEventRepository.emit(
                type = RunEventType.NOTE,
                title = FORK_TITLE,
                detail = "分叉 → ${creation.mission.id}（${stats.files} 个文件）" +
                    if (instruction.isBlank()) "" else "：${instruction.take(120)}",
                missionId = source.id,
            )
            runEventRepository.emit(
                type = RunEventType.NOTE,
                title = FORK_TITLE,
                detail = "自 ${source.id} 分叉（${stats.files} 个文件）",
                missionId = creation.mission.id,
            )
        }

        return Result(
            missionId = creation.mission.id,
            workspaceId = workspace.id,
            fileCount = stats.files,
            started = creation.started,
            launchError = creation.launchError,
        )
    }

    /** 工作区名必须唯一；重名时依次加 (2)、(3)……而不是让用户先改名。 */
    private suspend fun createWorkspace(title: String) = workspaceRepository.let { repo ->
        val base = "${title.trim().ifBlank { "任务" }}$FORK_TITLE_SUFFIX"
        val taken = runCatching { repo.getAll().map { it.name }.toSet() }.getOrDefault(emptySet())
        if (base !in taken) return@let repo.create(base)
        var index = 2
        while (index < 100) {
            val candidate = "$base ($index)"
            if (candidate !in taken) return@let repo.create(candidate)
            index++
        }
        repo.create("$base ${System.currentTimeMillis()}")
    }

    companion object {
        /** 分叉的 journal 标题（两边各一条）。 */
        const val FORK_TITLE: String = "fork"
    }
}
