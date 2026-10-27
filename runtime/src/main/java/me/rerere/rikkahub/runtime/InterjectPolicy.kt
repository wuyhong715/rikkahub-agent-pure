package me.rerere.rikkahub.runtime

import me.rerere.ai.ui.MessageInterject
import me.rerere.ai.ui.UIMessagePart

/**
 * 批 I — 全局插话（steer）的纯规则。
 *
 * 插话的投递链路本身在 runtime 里（[me.rerere.rikkahub.runtime.tools.ConversationInjections]
 * 排队 → GenerationLoop 在每次模型请求前排空），本对象只回答三个可以脱离 Android 测试的问题：
 *
 * 1. [plainTextOf]：这条输入能不能当插话？（只有纯文本可以；带图片/文件的消息语义复杂，
 *    继续走排队，等这一轮结束再处理。）
 * 2. [canInterject]：现在有没有插话的落点？（会话存在 + 正在生成 + 有文本。不再要求任务会话：
 *    普通对话里用户打断方向的诉求和任务里是一样的。）
 * 3. [injectionText]：塞给模型的那段合成提示词 —— 任务会话沿用原来的措辞，普通会话换一份，
 *    并且必须说清「这条和记录里刚落的那条用户消息是同一件事」，否则模型会把同一句话当成
 *    两条独立要求。
 *
 * [stage] 决定 UI 上那行标记写什么。宁可保守：只有当本轮确定还有下一次模型请求
 * （任务轮 / 还有未完成的工具执行）时才说「下一步生效」，其余情况说「本回合结束后生效」，
 * 因为它们会被排队到用户的下一条消息之前才 drain。绝不显示「已生效」——那是谎报。
 */
object InterjectPolicy {

    /**
     * 从输入内容里取出可插话的纯文本；不是纯文本（含图片/文件/音频等）或为空时返回 null。
     * 多个 Text part 按顺序用换行拼接（输入框的附件会被放进 `messageContent`，这里只剩文本）。
     */
    fun plainTextOf(parts: List<UIMessagePart>): String? {
        if (parts.isEmpty()) return null
        if (parts.any { it !is UIMessagePart.Text }) return null
        val text = parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        return text.ifEmpty { null }
    }

    /** 插话的全部前置条件：有会话、正在生成、有文本。 */
    fun canInterject(hasSession: Boolean, isGenerating: Boolean, plainText: String?): Boolean =
        hasSession && isGenerating && !plainText.isNullOrBlank()

    /**
     * 合成进下一个模型请求的提示词。
     *
     * [inMission] 为 true 时沿用任务会话原来的「立即生效」版本；普通会话用一份更短的版本，
     * 末句是关键：transcript 里同时会落一条原文（`answer = false`），模型不能把它当两条要求。
     */
    fun injectionText(text: String, inMission: Boolean): String {
        val trimmed = text.trim()
        return if (inMission) {
            buildString {
                appendLine("[用户插话 · 立即生效] 用户在你执行本轮任务的过程中补充了指令：")
                appendLine(trimmed)
                appendLine("从下一步开始按这个调整方向；已经完成且仍然有效的部分不要重做。")
                appendLine("（这条指令已记录在会话里，本轮结束后仍然有效。）")
            }.trimEnd()
        } else {
            buildString {
                appendLine("[用户插话] 用户在你生成过程中补充/纠正：")
                appendLine(trimmed)
                appendLine("从下一步开始按这个调整；已经说清楚的部分不要重复。")
                appendLine("（这条与记录里刚出现的用户消息是同一件事，不是新增要求。）")
            }.trimEnd()
        }
    }

    /**
     * UI 标记的档位（**不是** 「已生效」）。
     *
     * - 任务轮：一轮任务在 `mission_report` 之前会持续发起模型请求，下一步几乎必然存在。
     * - 有未完成的工具执行：工具结果回来后必然还有一次模型请求。
     * - 其余（普通对话的纯文字回答）：本回合可能不再请求模型，插话要等到用户的下一条消息，
     *   所以只能承诺「本回合结束后生效」。
     */
    fun stage(inMission: Boolean, hasUnfinishedToolWork: Boolean): MessageInterject =
        if (inMission || hasUnfinishedToolWork) MessageInterject.NEXT_STEP
        else MessageInterject.AFTER_TURN
}
