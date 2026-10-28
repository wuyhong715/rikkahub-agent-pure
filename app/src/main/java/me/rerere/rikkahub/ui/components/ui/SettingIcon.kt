package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.Radius

/**
 * 设置项前面的图标块。
 *
 * 之前所有设置行的图标都是一个裸的 `Icon`，靠 24dp 的默认尺寸站在 56dp 高的行里：
 * 视觉重量太轻，一屏二十行扫下来像一堆漂浮的小符号，而且整页只有一种颜色，
 * 看不出哪几行属于同一类。
 *
 * 这里给图标套一层 34dp 的浅色圆角块（圆角 10dp，与 [Radius.chip] 同族），
 * 底色是主色 12% 的透明度，图标本身用主色。色相由调用方给：设置首页按「分组」
 * 分配主色 / 次色 / 三色，于是外观、模型、设备、连接四组在扫读时能靠色相分开，
 * 而不用读标题。
 *
 * @param tint 图标与底色的色相。默认主色。
 * @param size 图标块边长。默认 34dp——比 ListItem 的图标槽（40dp）略小，
 *   这样它读起来是一个「徽章」而不是一个按钮。
 */
@Composable
fun SettingIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 34.dp,
    iconSize: Dp = 18.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(Radius.chip + 2.dp))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}
