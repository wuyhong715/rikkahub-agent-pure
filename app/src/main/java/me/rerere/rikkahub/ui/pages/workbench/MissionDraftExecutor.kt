package me.rerere.rikkahub.ui.pages.workbench

import kotlin.uuid.Uuid
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.speaker
import me.rerere.rikkahub.data.mission.ConversationBrief
import me.rerere.rikkahub.data.mission.MissionLimits
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.runtime.util.JsonInstant
import me.rerere.rikkahub.service.mission.LaunchSource
import me.rerere.rikkahub.service.mission.MissionConversationEcho
import me.rerere.rikkahub.service.mission.MissionCreationService
import me.rerere.rikkahub.service.mission.MissionLauncher
import me.rerere.rikkahub.workflow.model.TriggerSpec

/**
 * 把创建表的一份 [MissionDraft] 变成任务：建行 -> 配工作区 / 审批 / 预算 / 触发 -> 首轮开跑
 * -> 往源对话回一句话。
 *
 * 抽出来是因为**从对话开始任务**和**从工作台开始任务**必须是同一条路径：两边各抄一份建任务的
 * 逻辑，迟早会出现「从对话建的任务没有工作区」「从工作台建的任务预算不同」这种鬼故事。
 * 差别只有一个参数：[execute] 的 `sourceConversationId`。
 *
 * [sourceConversationId] 非空时做两件事，且都只做一次：
 * - 首轮简报前拼上源对话摘要（[ConversationBrief]，只走 Create 轮）；
 * - 把来源写进 `limits_json`，之后任务落定时由 [MissionConversationEcho] 回推消息。
 */
class MissionDraftExecutor(
    private val missionRepository: MissionRepository,
    private val missionCreation: MissionCreationService,
    private val missionLauncher: MissionLauncher,
    private val conversationRepository: ConversationRepository,
    private val echo: MissionConversationEcho,
) {

    /** `missionId` 永远不为空（行已经建好）；[launchError] 非空表示任务在、但第一轮没跑起来。 */
    data class Result(
        val missionId: String,
        val launchError: String? = null,
    )

    suspend fun execute(draft: MissionDraft, sourceConversationId: String? = null): Result {
        val sourceId = sourceConversationId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf { raw -> runCatching { Uuid.parse(raw) }.isSuccess }

        // 批 B — trigger start mode: serialize the spec; null = the immediate wish-style path.
        val triggerJson = draft.trigger?.let { spec ->
            runCatching { JsonInstant.encodeToString<TriggerSpec>(spec) }.getOrNull()
        }
        // Default workspace + workflow seeding: a mission created without an explicit workspace
        // used to have no `workflows/` folder at all, so the picker and the model's chooser were
        // always empty. The creation service owns that guarantee.
        val workspaceId = draft.workspaceId ?: runCatching {
            missionCreation.defaultWorkspace().id
        }.getOrNull()
        if (workspaceId != null) {
            runCatching { missionCreation.seedWorkflows(workspaceId) }
        }

        val mission = missionRepository.create(
            title = draft.title,
            description = draft.description,
            acceptance = draft.goals,
            conversationId = null,
            triggerJson = triggerJson,
            cooldownSeconds = if (triggerJson != null) draft.cooldownSeconds else 0,
            maxRunsPerDay = if (triggerJson != null) draft.maxRunsPerDay else null,
            workflowPath = draft.workflowPath,
        )
        missionRepository.updateWorkspaceAndAssistant(mission.id, workspaceId, draft.assistantId)
        draft.approvalMode?.let { missionRepository.updateApprovalMode(mission.id, it) }

        // 批 4 — 预算档位：draft 里没填的字段一律取 MissionLimits 的默认值（标准档），
        // 不再在这里抄一份 5 / 2 / 500_000 的魔数（那样改默认值等于改两处，迟早不同步）。
        // 来源对话也算「必须写 limits」的理由：它住在 limits_json 里（见 MissionLimits 的说明）。
        val hasBudgetOverride = draft.maxResumeRounds != null || draft.stallRounds != null ||
            draft.tokenBudget != null || draft.roundTimeoutMinutes != null ||
            draft.wrapUpMinutes != null || draft.requireConfirmation
        if (hasBudgetOverride || sourceId != null) {
            val defaults = MissionLimits()
            missionRepository.updateLimits(
                mission.id,
                defaults.copy(
                    maxResumeRounds = draft.maxResumeRounds ?: defaults.maxResumeRounds,
                    stallRounds = draft.stallRounds ?: defaults.stallRounds,
                    tokenBudget = draft.tokenBudget ?: defaults.tokenBudget,
                    requireConfirmation = draft.requireConfirmation,
                    roundTimeoutMinutes = draft.roundTimeoutMinutes ?: defaults.roundTimeoutMinutes,
                    wrapUpMinutes = draft.wrapUpMinutes ?: defaults.wrapUpMinutes,
                    sourceConversationId = sourceId,
                ),
            )
        }

        if (triggerJson != null) {
            // Trigger-driven: MissionScheduler picks the mission up; do not launch now.
            runCatching {
                missionRepository.logEvent(
                    mission.id,
                    me.rerere.rikkahub.data.runevent.RunEventType.NOTE,
                    title = "trigger_armed",
                    detail = draft.trigger?.let { it::class.simpleName },
                )
            }
            return Result(mission.id)
        }

        val brief = sourceId?.let { briefFor(it) }
        val outcome = missionLauncher.launch(
            missionId = mission.id,
            source = LaunchSource.Create,
            firstRoundBrief = brief,
        )
        if (!outcome.ok) {
            return Result(mission.id, launchError = outcome.error)
        }

        // 回声：读一次最新的行，拿到刚写进去的 limits（来源对话 id 在里面）。
        val fresh = runCatching { missionRepository.getById(mission.id) }.getOrNull() ?: mission
        echo.onStarted(fresh)
        return Result(mission.id)
    }

    /**
     * 源对话的摘要。取不到（会话已删、库忙）就返回 null —— 任务照常开跑，只是没有背景。
     */
    suspend fun briefFor(conversationId: String): String? {
        val uuid = runCatching { Uuid.parse(conversationId) }.getOrNull() ?: return null
        val conversation = runCatching { conversationRepository.getConversationById(uuid) }
            .getOrNull() ?: return null
        return ConversationBrief.compose(conversation.currentMessages.mapNotNull { it.toBriefLine() })
    }
}

/**
 * 一条消息 -> 摘要里的一行。
 *
 * 角色口径：App 自己写进会话的（任务简报 / 汇报 / 系统通知）算「系统」，用户说的算「用户」，
 * 其余算「模型」。工具调用、图片、文件这些不是「话」，直接跳过 —— 摘要要的是对话在聊什么。
 */
internal fun UIMessage.toBriefLine(): ConversationBrief.Line? {
    val text = parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
    if (text.isBlank()) return null
    val briefRole = when {
        speaker.isApp -> ConversationBrief.Role.SYSTEM
        role == MessageRole.USER -> ConversationBrief.Role.USER
        else -> ConversationBrief.Role.MODEL
    }
    return ConversationBrief.Line(briefRole, text)
}
