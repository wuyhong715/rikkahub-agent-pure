package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.R

val base = Typography()

/**
 * 排版：只调三件事，不动字号阶梯。
 *
 * 1. **大字收字距**。display/headline 默认按西文单字调过 tracking，中文与混合排版下会显得松，
 *    而且 Material 默认的字距在 28sp 以上几乎是「散开」的观感；大字号统一收到 -0.1 ~ -0.5sp。
 * 2. **正文给行高**。默认 bodyLarge 是 24sp/16sp = 1.5，读长回答偏挤；这里提到 1.55 ~ 1.6，
 *    中文段落之间的呼吸感立刻出来，同时不增加任何控件高度（控件用的是 title/label）。
 * 3. **标签加微字距**。label* 用 +0.1 ~ +0.5sp，让「元信息」（token 数、时间、模型名）在视觉上
 *    退到正文之后，而不是靠继续降低对比度去抢安静。
 *
 * 字号阶梯本身保持 Material 原值：这是无障碍与系统字号缩放最稳的一档，改它会连带破坏布局。
 */
val Typography = base.copy(
    displayLarge = base.displayLarge.copy(
        lineHeight = 62.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = base.displayMedium.copy(
        lineHeight = 52.sp,
        letterSpacing = (-0.5).sp,
    ),
    displaySmall = base.displaySmall.copy(
        lineHeight = 44.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineLarge = base.headlineLarge.copy(
        lineHeight = 40.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = base.headlineMedium.copy(
        lineHeight = 36.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = base.headlineSmall.copy(
        lineHeight = 32.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleLarge = base.titleLarge.copy(
        lineHeight = 28.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = base.titleMedium.copy(
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = base.titleSmall.copy(
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = base.bodyLarge.copy(
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = base.bodyMedium.copy(
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = base.bodySmall.copy(
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = base.labelLarge.copy(
        letterSpacing = 0.1.sp,
    ),
    labelMedium = base.labelMedium.copy(
        letterSpacing = 0.3.sp,
    ),
    labelSmall = base.labelSmall.copy(
        letterSpacing = 0.5.sp,
    ),
)

// Set of Material typography styles to start with
//val Typography = Typography(
//    displayLargeEmphasized = base.displayLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Display.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    displayMediumEmphasized = base.displayMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Display.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    displaySmallEmphasized = base.displaySmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Display.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    headlineLargeEmphasized = base.headlineLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Headline.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    headlineMediumEmphasized = base.headlineMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Headline.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    headlineSmallEmphasized = base.headlineSmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Headline.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    titleLargeEmphasized = base.titleLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Title.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    titleMediumEmphasized = base.titleMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Title.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    titleSmallEmphasized = base.titleSmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Title.Emphasized.Small,
//        fontWeight = FontWeight.Bold
//    ),
//    bodyLargeEmphasized = base.bodyLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Body.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    bodyMediumEmphasized = base.bodyMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Body.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    bodySmallEmphasized = base.bodySmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Body.Emphasized.Small,
//        fontWeight = FontWeight.Bold
//    ),
//    labelLargeEmphasized = base.labelLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Label.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    labelMediumEmphasized = base.labelMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Label.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    labelSmallEmphasized = base.labelSmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Label.Emphasized.Small,
//        fontWeight = FontWeight.Bold
//    ),
//)

@OptIn(ExperimentalTextApi::class)
val JetbrainsMono = FontFamily(
    Font(
        resId = R.font.jetbrains_mono,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Normal.weight),
        )
    )
)
