package me.rerere.rikkahub.data.mission

/**
 * 批 4 — 任务会话在对话列表里的可见性。
 *
 * 任务的执行会话是任务自己的私有 transcript，不是一个「对话」：它由 `MissionLauncher` 创建、
 * 由督导写交接、由模型在里面跑工具。以前它和用户的日常对话混在同一份列表里，用户分不清哪条
 * 是自己在聊、哪条是系统在干活（M102 反馈②）。
 *
 * 这一轮**不加数据库列**（`ConversationEntity.kind` 要 bump schema 版本 + 生成 DTO +
 * 更新导入校验，代价是两轮 CI），而是在视图层用「任务表里被引用的 conversationId」做过滤：
 * 关系和 `kind` 列能表达的完全一样，还天然支持「老数据不用回填」。
 *
 * 规则：
 * - 任务的 `conversationId` 是唯一的真相来源；空白一律当作「没有绑定会话」。
 * - 设置项 `showMissionConversations` 打开时完全不过滤（可逆，用户能立刻看到原始列表）。
 * - 过滤器只认 id 字符串，不认 [Conversation]，这样纯函数可以脱离 Room 单测。
 */
object MissionConversationFilter {

    /** 任务引用的执行会话 id 集合（跳过 null 与空白）。 */
    fun conversationIds(missionConversationIds: Iterable<String?>): Set<String> =
        missionConversationIds.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.toSet()

    /** 单条是否应该出现在对话列表里。 */
    fun isVisible(
        conversationId: String?,
        hiddenIds: Set<String>,
        showMissionConversations: Boolean,
    ): Boolean {
        if (showMissionConversations || hiddenIds.isEmpty()) return true
        return conversationId == null || conversationId !in hiddenIds
    }

    /** 过滤一页（或一组）对话，顺序保持不变。 */
    fun <T> visible(
        items: List<T>,
        hiddenIds: Set<String>,
        showMissionConversations: Boolean,
        idOf: (T) -> String?,
    ): List<T> {
        if (showMissionConversations || hiddenIds.isEmpty()) return items
        return items.filter { isVisible(idOf(it), hiddenIds, showMissionConversations) }
    }

    /**
     * 抽屉「任务」入口上的角标：还在进行中的任务数。
     *
     * 用 `status == ACTIVE` 而不是 `execution_state == running`：用户想看的是「我还有几件事没结束」，
     * 而两轮之间的 IDLE 空档不该让角标闪。
     */
    fun activeCount(statuses: Iterable<String>): Int =
        statuses.count { it == MissionStatus.ACTIVE.name }
}
