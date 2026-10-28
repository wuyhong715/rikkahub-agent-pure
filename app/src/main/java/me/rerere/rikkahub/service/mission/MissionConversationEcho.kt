package me.rerere.rikkahub.service.mission

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.limits

/**
 * 批 4（M102 反馈④）— 任务往**源对话**说话的那一半。
 *
 * 以前任务的一生都发生在自己那个隐藏的执行会话里：用户从对话里发起（这次新加的入口）然后
 * 就再也不知道它跑到哪了，只能自己去工作台翻。回声就是那条回去的路：
 *
 * - 任务开始 -> 「已开始任务「X」· 第 1 轮」；
 * - 任务待验收 / 完成 / 阻塞 -> 状态 + 一句话结果 + 产物数。
 *
 * 落点用的是 `ChatService.sendMessage` 的 `answer = false` 通道（和 steer 补记 transcript
 * 同一条路）：消息进会话、`origin = APP_REPORT`、**不触发模型生成**。也就是说回声是纯通知，
 * 不会把用户的对话变成一次新请求、也不会烧 token。
 *
 * 幂等：`missionId + 状态` 在进程内只推一次（[pushed]）。选内存而不是 journal，是因为回声是
 * 「阶段变化」的派生动作——进程重启后不会重放这些变化（任务状态没有变），所以不需要持久化
 * 去重表；反过来，把它写进 journal 会让时间线里多出一堆 echo:REVIEW 噪音。
 * 同一状态在生命周期里再来一次（REVIEW -> ACTIVE -> REVIEW）也不再推：用户已经看过一次了。
 *
 * 没有 [me.rerere.rikkahub.data.mission.MissionLimits.sourceConversationId] 的任务（工作台
 * 创建 / 老任务）走的是空实现，行为和以前一模一样。
 */
class MissionConversationEcho(
    /** 往会话里投一条 App 消息（DI 接 ChatService；answer=false 语义）。 */
    private val post: suspend (conversationId: String, text: String) -> Unit,
) {

    private val pushed = ConcurrentHashMap<String, Boolean>()

    /** 任务第 1 轮开跑（由创建方在 `LaunchOutcome.ok` 之后调用）。 */
    suspend fun onStarted(mission: MissionEntity, round: Int = 1) {
        val conversationId = mission.sourceConversation() ?: return
        if (!claim(mission.id, KEY_STARTED)) return
        deliver(conversationId, MissionEchoText.started(mission.title, round))
    }

    /**
     * 任务进入终态或等人状态。只认 [MissionStatus.REVIEW] / [MissionStatus.DONE] /
     * [MissionStatus.BLOCKED]；其它状态（ACTIVE / ARCHIVED）不打扰用户。
     *
     * [summary] 是督导 / 详情页已经算好的那一句话（mission_report 的摘要、卡住的原因），
     * null 时只报状态。
     */
    suspend fun onSettled(mission: MissionEntity, status: MissionStatus, summary: String? = null) {
        if (status != MissionStatus.REVIEW &&
            status != MissionStatus.DONE &&
            status != MissionStatus.BLOCKED
        ) {
            return
        }
        val conversationId = mission.sourceConversation() ?: return
        if (!claim(mission.id, status.name)) return
        // 产物数就在任务行上（RunEvent ARTIFACT 每次 +1），不用再查一次 artifacts 表。
        deliver(
            conversationId,
            MissionEchoText.settled(mission.title, status, summary, mission.artifactCount),
        )
    }

    /** 只在第一次见到「这个任务的这个阶段」时返回 true。 */
    private fun claim(missionId: String, key: String): Boolean =
        pushed.putIfAbsent("$missionId:$key", true) == null

    private suspend fun deliver(conversationId: String, text: String) {
        // 回声失败绝不能影响任务本身：投不进去就当没说过（用户还有通知栏和工作台）。
        runCatching { post(conversationId, text) }
    }

    private fun MissionEntity.sourceConversation(): String? =
        limits.sourceConversationId
            ?.takeIf { it.isNotBlank() && it != conversationId }
            ?.takeIf { raw -> runCatching { Uuid.parse(raw) }.isSuccess }

    private companion object {
        const val KEY_STARTED = "STARTED"
    }
}
