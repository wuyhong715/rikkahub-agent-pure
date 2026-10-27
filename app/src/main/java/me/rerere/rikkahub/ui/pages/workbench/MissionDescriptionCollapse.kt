package me.rerere.rikkahub.ui.pages.workbench

/**
 * 任务详情 Hero 卡片里「目标原文」的折叠规则。
 *
 * 任务描述在 Moxw 里通常就是整段任务提示词（几千字），展开渲染会把状态药丸、验收进度、
 * 开始/停止这些真正要看的控件全部挤出首屏。这里只做纯判断：折叠三行，只有文本真的溢出
 * 时才给「展开」入口——短描述不该多出一个没用的按钮。
 *
 * 与 `ui/components/message/UserMessageCollapse` 同一套思路（纯函数 + 单元测试），
 * 差别是这里按行数而不是像素高度判断，因为 Markdown/纯文本的行高由主题决定。
 */
internal object MissionDescriptionCollapse {

    /** 折叠时最多显示的行数。 */
    const val COLLAPSED_LINES = 3

    data class Layout(val maxLines: Int, val showToggle: Boolean)

    /**
     * @param expanded 用户是否点了「展开」
     * @param overflows 折叠状态下文本是否真的被截断（来自 `TextLayoutResult.hasVisualOverflow`）
     */
    fun layout(expanded: Boolean, overflows: Boolean): Layout = when {
        expanded -> Layout(maxLines = Int.MAX_VALUE, showToggle = true)
        overflows -> Layout(maxLines = COLLAPSED_LINES, showToggle = true)
        else -> Layout(maxLines = COLLAPSED_LINES, showToggle = false)
    }
}
