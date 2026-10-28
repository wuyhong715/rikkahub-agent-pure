package me.rerere.rikkahub.ui.pages.chat

/** 「以此开始任务」时任务标题的长度上限（字）。 */
internal const val MAX_TITLE_CHARS: Int = 40

/**
 * 从任务目标里推出一个标题：取第一行非空内容，压到 [maxChars] 字。
 *
 * 一条几百字的语音转写直接当标题，会在抽屉 / 工作台的列表里撑成一整句话；截断时补省略号，
 * 而不是把标题切成半个字。目标正文本身完整留在 description 里，没有任何信息损失。
 */
internal fun missionTitleFromGoal(goal: String, maxChars: Int = MAX_TITLE_CHARS): String {
    val firstLine = goal.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: return ""
    return if (firstLine.length <= maxChars) firstLine else firstLine.take(maxChars) + "…"
}
