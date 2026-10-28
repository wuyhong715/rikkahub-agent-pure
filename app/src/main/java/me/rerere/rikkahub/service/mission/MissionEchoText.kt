package me.rerere.rikkahub.service.mission

import me.rerere.rikkahub.data.mission.MissionStatus

/**
 * 回声的文案（纯函数，可单测）。
 *
 * 这几句话是用户判断「要不要现在去看一眼」的唯一依据，所以：标题空着也要有个说法，结果再长
 * 也只留一句话（整段贴进对话等于把任务报告又抄一遍），产物数只在真有时才出现。
 *
 * 文案跟督导的通知（`任务完成：…` / `任务待确认：…`）同一套中文口径：这是 App 自己说的话，
 * 不走 strings.xml 的 UI 词表。
 */
object MissionEchoText {

    /** 摘要最多留多少字，和 `ConversationBrief` 的口径一致。 */
    const val MAX_SUMMARY_CHARS: Int = 180

    /** 任务第 1 轮开跑。 */
    fun started(title: String, round: Int): String = buildString {
        append("已开始任务「").append(title.display()).append("」· 第 ")
        append(round.coerceAtLeast(1)).append(" 轮\n\n")
        append("它在后台独立运行，进度和结果会回传到这个对话；现在可以继续说别的。")
    }

    /** 待验收 / 完成 / 阻塞。 */
    fun settled(title: String, status: MissionStatus, summary: String?, artifacts: Int): String {
        val name = title.display()
        val head = when (status) {
            MissionStatus.REVIEW -> "任务「$name」跑完了，等你验收。"
            MissionStatus.DONE -> "任务「$name」已完成。"
            MissionStatus.BLOCKED -> "任务「$name」卡住了，需要你处理。"
            else -> "任务「$name」状态更新：${status.name}。"
        }
        val line = summary?.replace(WHITESPACE, " ")?.trim().orEmpty()
        return buildString {
            append(head)
            if (line.isNotEmpty()) {
                append("\n\n> ")
                append(if (line.length <= MAX_SUMMARY_CHARS) line else line.take(MAX_SUMMARY_CHARS) + "…")
            }
            if (artifacts > 0) {
                append("\n\n产物 ").append(artifacts).append(" 个，在任务详情里查看。")
            }
        }
    }

    private val WHITESPACE = Regex("\\s+")

    private fun String.display(): String = trim().ifEmpty { "未命名任务" }
}
