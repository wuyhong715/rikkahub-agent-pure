package me.rerere.rikkahub.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberCurrentColorMode
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }

private val AMOLED_DARK_BACKGROUND = Color(0xFF000000)

/**
 * 形状阶梯：三档圆角，全应用统一从这里取。
 *
 * 旧的默认阶梯是 4/8/12/16/28，中等组件（卡片、输入条、弹层按钮）挤在 12 与 16 之间，
 * 同一屏能同时出现四种「差不多」的圆角。这里改成 6/10/16/20/28：
 * 小控件更硬朗（6/10），大容器更松弛（20/28），中间留出 16 这一档给卡片与输入区，
 * 视觉上能一眼分出「按钮 / 容器 / 弹层」三层，而不是一层层递增的模糊地带。
 */
private val RikkaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun RikkahubTheme(
    colorMode: ColorMode = rememberCurrentColorMode(),
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    val colorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val theme = findThemeById(settings.themeId, settings.customThemes)
                ?: findPresetTheme(settings.themeId)
            theme.getColorScheme(dark = darkTheme)
        }
    }
    val colorSchemeConverted = remember(darkTheme, amoledDarkMode, colorScheme) {
        if (darkTheme && amoledDarkMode) {
            colorScheme.copy(
                background = AMOLED_DARK_BACKGROUND,
                surface = AMOLED_DARK_BACKGROUND,
            )
        } else {
            colorScheme
        }
    }
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors

    // 更新状态栏图标颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(view, darkTheme) {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            val previousLightStatusBars = controller.isAppearanceLightStatusBars
            val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
            controller.apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            onDispose {
                // 嵌套主题（如终端的深色主题）退出时恢复原有系统栏图标颜色。
                controller.isAppearanceLightStatusBars = previousLightStatusBars
                controller.isAppearanceLightNavigationBars = previousLightNavigationBars
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalExtendColors provides extendColors,
        LocalOverscrollFactory provides null
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorSchemeConverted,
            typography = Typography,
            shapes = RikkaShapes,
            content = content,
            // 标准动效而不是 expressive：这是长时间挂着、要一直盯着看的界面，
            // 弹性回弹的位移在反复出现的列表/消息上会变成噪音。
            motionScheme = MotionScheme.standard()
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current
