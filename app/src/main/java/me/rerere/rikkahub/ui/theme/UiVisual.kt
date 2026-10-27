package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 视觉基元（tokens）—— 整套界面的间距、圆角、边界强度只从这里取值。
 *
 * 为什么要有这一层：工作台、任务详情、主聊天原本各写各的 `12.dp / 16.dp / 8.dp`，
 * 同一层级的卡片间距能差 6dp，圆角从 12 到 20 混用，读起来像三个不同的产品。
 * 这里只统一“尺度”，不碰颜色：颜色一律走 `MaterialTheme.colorScheme`，这样用户的
 * 动态取色与自定义主题不会被覆盖。
 *
 * 刻度按 4dp 基准：2 / 4 / 8 / 12 / 16 / 24，够用且不会出现 6、10、14 这类碎值。
 */
object Spacing {

    /** 2dp — 图标与文字贴在一起时的缝。 */
    val xxs = 2.dp

    /** 4dp — 同一行内的元素间距。 */
    val xs = 4.dp

    /** 8dp — 卡片内部行间距、列表项之间。 */
    val sm = 8.dp

    /** 12dp — 卡片内边距的紧凑档。 */
    val md = 12.dp

    /** 16dp — 标准卡片内边距、页面左右留白。 */
    val lg = 16.dp

    /** 24dp — 空态、弹层这类需要呼吸的地方。 */
    val xl = 24.dp

    /**
     * 分组之间的间距。
     *
     * 关键的一条：组间距必须严格大于组内间距（[sm]），否则“标题 + 卡片”会和下一组糊成一片，
     * 屏幕上只剩一片等距的卡片。20dp 是 8dp 的 2.5 倍，肉眼能看出分层。
     */
    val sectionGap = 20.dp
}

/**
 * 圆角刻度。只保留三档，避免同一屏出现 12/14/16/18 四种“差不多的圆角”。
 */
object Radius {

    /** 8dp — 小标签、内嵌块。 */
    val chip = 8.dp

    /** 16dp — 卡片、Hero、统计条。 */
    val card = 16.dp

    /** 24dp — 底部弹层。 */
    val sheet = 24.dp
}

/**
 * 线条强度。
 *
 * 全部是 alpha，不是颜色：叠在 `outlineVariant` / `onSurface` 上，所以深色浅色、
 * 用户自定义主题下都能自动适配。卡片不再靠 `tonalElevation` 的阴影区分层级
 * （阴影在浅色主题几乎看不见，在深色主题又像脏污），改用一条极淡的描边。
 */
object Hairline {

    /** 卡片描边：能看出边界，但不会把卡片框成表格。 */
    const val BORDER_ALPHA = 0.45f

    /** 分隔线：比卡片描边再淡一档，只做“这里断开了”的提示。 */
    const val DIVIDER_ALPHA = 0.6f
}

/**
 * 统一的分组卡片容器。
 *
 * 之前每个页面各自 `Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp)`，
 * 结果：浅色主题下几乎看不出边界，深色主题下又出现一层灰雾。这里固定为
 * `surfaceContainerLow` 底色 + 极淡描边，层级差靠色阶而不是阴影，深浅主题都稳。
 *
 * @param onClick 传了就变成可点卡片（有水波纹），否则是纯展示容器。
 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(Radius.card),
    content: @Composable ColumnScope.() -> Unit,
) {
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    val border = BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = Hairline.BORDER_ALPHA),
    )
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = border,
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = border,
        ) {
            Column(content = content)
        }
    }
}

/**
 * 分组标题。
 *
 * 定义成组件而不是每处手写 `Text(style = titleSmall)`，是为了让“组标题”和“卡片标题”
 * 有固定的一档差距：组标题用 `titleSmall` + `onSurfaceVariant`（安静，但比正文重），
 * 卡片标题才用 `onSurface`。之前两者同为 titleSmall + onSurface，标题和内容互相抢。
 *
 * @param topGap 组标题上方的留白。默认 [Spacing.md]；同一屏的第一个标题可以传 0。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    topGap: Dp = Spacing.md,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.invoke()
    }
}
