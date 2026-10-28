package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

/**
 * 墨（Ink）—— 默认主题。
 *
 * 取向：纸与墨。浅色是带一点暖调的白纸，深色是带一点绿调的炭黑，两者都**不用纯白/纯黑**，
 * 因为纯白在夜里刺眼、纯黑在白天像关机。强调色只有一个：低饱和的青瓷。
 *
 * 层级全部靠 `surfaceContainer*` 的色阶 + 一条极淡描边表达，不靠阴影：
 * 阴影在浅色主题上几乎看不见，在深色主题上又像一块脏污。
 *
 * 语义分工：
 * - primary（青瓷）= 可交互 / 正在生效；
 * - tertiary（陶土）= 进行中、需要留意的后台任务；
 * - error = 失败。
 * 三者色相相距足够远，色弱用户也能靠明度区分。
 */
val InkThemePreset by lazy {
    PresetTheme(
        id = "ink",
        name = {
            Text(stringResource(id = R.string.theme_name_ink))
        },
        standardLight = inkLightScheme,
        standardDark = inkDarkScheme,
    )
}

// ── 浅色：暖白纸 ──────────────────────────────────────────────────────────────
private val primaryLight = Color(0xFF1F5F5B)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFCFE9E4)
private val onPrimaryContainerLight = Color(0xFF04302D)
private val secondaryLight = Color(0xFF4E5A57)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFE3EBE7)
private val onSecondaryContainerLight = Color(0xFF2A3532)
private val tertiaryLight = Color(0xFF8A5A2B)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFF6E3CE)
private val onTertiaryContainerLight = Color(0xFF4A2C0D)
private val errorLight = Color(0xFFB23A2E)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFBE2DE)
private val onErrorContainerLight = Color(0xFF6E140C)
private val backgroundLight = Color(0xFFFBFAF7)
private val onBackgroundLight = Color(0xFF17191A)
private val surfaceLight = Color(0xFFFBFAF7)
private val onSurfaceLight = Color(0xFF17191A)
private val surfaceVariantLight = Color(0xFFECECE6)
private val onSurfaceVariantLight = Color(0xFF5C6363)
private val outlineLight = Color(0xFF8E9691)
private val outlineVariantLight = Color(0xFFDCDDD7)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF2B302E)
private val inverseOnSurfaceLight = Color(0xFFF1F2EE)
private val inversePrimaryLight = Color(0xFF8FD3CB)
private val surfaceDimLight = Color(0xFFE3E4DE)
private val surfaceBrightLight = Color(0xFFFDFCF9)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF7F6F2)
private val surfaceContainerLight = Color(0xFFF2F1EC)
private val surfaceContainerHighLight = Color(0xFFECEBE5)
private val surfaceContainerHighestLight = Color(0xFFE6E5DE)

// ── 深色：炭黑带一点绿 ────────────────────────────────────────────────────────
private val primaryDark = Color(0xFF86CFC7)
private val onPrimaryDark = Color(0xFF04322E)
private val primaryContainerDark = Color(0xFF1C4744)
private val onPrimaryContainerDark = Color(0xFFBEE9E3)
private val secondaryDark = Color(0xFFB3C0BB)
private val onSecondaryDark = Color(0xFF1D2724)
private val secondaryContainerDark = Color(0xFF2C3734)
private val onSecondaryContainerDark = Color(0xFFD5E2DD)
private val tertiaryDark = Color(0xFFE0B183)
private val onTertiaryDark = Color(0xFF42280E)
private val tertiaryContainerDark = Color(0xFF5B3C1D)
private val onTertiaryContainerDark = Color(0xFFF8DDBE)
private val errorDark = Color(0xFFF2A79C)
private val onErrorDark = Color(0xFF4E0F08)
private val errorContainerDark = Color(0xFF7A241A)
private val onErrorContainerDark = Color(0xFFFBDAD3)
private val backgroundDark = Color(0xFF0D100F)
private val onBackgroundDark = Color(0xFFE4E7E3)
private val surfaceDark = Color(0xFF0D100F)
private val onSurfaceDark = Color(0xFFE4E7E3)
private val surfaceVariantDark = Color(0xFF2A302E)
private val onSurfaceVariantDark = Color(0xFFA3ACA7)
private val outlineDark = Color(0xFF6B7A75)
private val outlineVariantDark = Color(0xFF262D2B)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFE4E7E3)
private val inverseOnSurfaceDark = Color(0xFF2B302E)
private val inversePrimaryDark = Color(0xFF1F5F5B)
private val surfaceDimDark = Color(0xFF0D100F)
private val surfaceBrightDark = Color(0xFF353B39)
private val surfaceContainerLowestDark = Color(0xFF080A09)
private val surfaceContainerLowDark = Color(0xFF121615)
private val surfaceContainerDark = Color(0xFF171C1A)
private val surfaceContainerHighDark = Color(0xFF1F2523)
private val surfaceContainerHighestDark = Color(0xFF272E2B)

private val inkLightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val inkDarkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)
