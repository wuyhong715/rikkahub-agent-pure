package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.Radius
import me.rerere.rikkahub.ui.theme.Spacing

/**
 * 全应用唯一的底部弹层骨架。
 *
 * 之前每一个 `ModalBottomSheet` 都是各写各的：圆角靠默认、面色靠默认、标题用 `titleLarge`
 * 硬写在一列的最上面、内边距 16dp / 24dp 随机、拖拽把手有的有有的没有。于是同一个 App 里
 * 「模型选择」「思考深度」「搜索服务」「附件」四种弹层像是四种不同的界面，从聊天页的输入条
 * 点进去像是跳到了另一个产品。
 *
 * 这里把弹层的「壳」收成一处：
 *  - 形状：上角 [Radius.sheet]（28dp），下角贴屏幕底；
 *  - 面色：`surfaceContainerLow`——比 `surface` 高半档，浮在内容之上，但不刺眼；
 *  - 描边：顶部一条发丝线（见 [MoxwSheetHeader] 之后由调用方按需加）；
 *  - 遮罩：`scrim` 32%，深浅主题同一个强度，弹层永远是「压住」内容的；
 *  - 把手：统一的 36×4dp 圆角短条（[MoxwSheetHandle]），而不是 M3 默认那根略粗的；
 *  - 标题：[MoxwSheetHeader] 统一为 titleMedium + bodySmall，左对齐、24dp 起边距。
 *
 * 内容仍然由调用方给，所以任何弹层都能换成这套壳而不改行为。
 *
 * @param sheetState 传进来是为了保留调用方自己的开合状态（例如需要在提交前先 `hide()`）。
 * 省略时用的是标准状态：可以被拖走，也可以在代码里 `hide()`。
 */
@Composable
fun MoxwSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberBottomSheetState(),
    title: String? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    /**
     * 拖拽把手。默认是统一的 [MoxwSheetHandle]；传 `null` 表示这个弹层不要把手
     * （内容本身自带标题栏、或弹层太矮时），传自己的 composable 则完全替换。
     */
    dragHandle: (@Composable (() -> Unit)?)? = { MoxwSheetHandle() },
    /**
     * 是否允许下滑关闭。默认允许。
     *
     * 有些弹层内容本身可滚动（例如代码编辑器、工具详情），下滑会被误判成「关掉弹层」，
     * 所以那些地方显式关掉它。
     */
    sheetGesturesEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = containerColor,
        tonalElevation = 0.dp,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
        dragHandle = dragHandle,
        sheetGesturesEnabled = sheetGesturesEnabled,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (title != null || subtitle != null || trailing != null) {
                MoxwSheetHeader(title = title, subtitle = subtitle, trailing = trailing)
            }
            content()
        }
    }
}

/**
 * 弹层把手。
 *
 * M3 默认的把手是 32×4dp 但用了 `onSurfaceVariant` 的高不透明度，在深色主题里像一道白线；
 * 这里降到 32%，并把宽度收到 36dp 之外的一点点视觉差（4dp 高、28dp 宽），让它是「提示」
 * 而不是「控件」——用户不需要点它，只是知道这里可以往下拖。
 */
@Composable
fun MoxwSheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = Spacing.md, bottom = Spacing.xs)
            .size(width = 36.dp, height = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
                shape = RoundedCornerShape(2.dp),
            )
    )
}

/**
 * 弹层标题区。
 *
 * 之前每个弹层的标题都是 `Text(titleLarge)` 顶在列的最上面，下面直接就是内容；标题和第一条
 * 内容之间没有任何分隔，扫一眼分不清「这是标题」还是「这是第一项」。这里把标题固定成
 * titleMedium（比 titleLarge 安静一档），副标题用 bodySmall 挂在下面，底部留 12dp 的呼吸。
 */
@Composable
fun MoxwSheetHeader(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.sm, bottom = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (leading != null) {
            leading()
        }
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                if (title != null) {
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 弹层里的一行：图标 + 标题 + 副标题 + 尾部。
 *
 * 选择器类弹层（助手、模型、思考深度、搜索服务、MCP、工作区）原本各画各的行：有的是
 * `ListItem`，有的是 `Card`，有的是 `Button`。同一次「换一个模型」的动作，在不同入口下
 * 走的是两套视觉。这里给出一行统一的：标题用 titleSmall，副标题用 bodySmall，选中态用
 * `primaryContainer` 铺底而不是换图标颜色——换颜色在深色主题里几乎看不出来。
 */
@Composable
fun MoxwSheetRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.field),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (leading != null) {
                leading()
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            }
        }
    }
}

/**
 * 弹层里的分隔线。
 *
 * 弹层内部常用 `HorizontalDivider()`，默认色是 `outlineVariant` 全不透明度，在深色主题里
 * 是一条硬边。这里给一档更淡的：能看出「这里断开了」，但不会把列表切成表格。
 */
@Composable
fun MoxwSheetDivider(modifier: Modifier = Modifier) {
    androidx.compose.material3.HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** 弹层里左右统一的留白（24dp，与标题区同一条基线）。 */
val MoxwSheetPadding = 24.dp

/** 弹层里列表项之间统一的行距。 */
val MoxwSheetItemSpacing = 4.dp
