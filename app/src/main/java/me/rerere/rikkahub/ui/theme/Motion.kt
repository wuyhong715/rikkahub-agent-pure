package me.rerere.rikkahub.ui.theme

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.Modifier

/**
 * 动效刻度。
 *
 * 这套界面的动效只有三个用途，对应三档时长——**不为好看而动，只为「发生了什么」提供因果**：
 *
 * - [enter]（160ms）：内容出现。消息、动作条、建议胶囊。必须比手指抬起的感知延迟短。
 * - [emphasis]（240ms）：容器形变。输入条展开、气泡高度变化、顶栏收起。
 * - [exit]（120ms）：内容消失。快于出现——消失不需要被看清。
 *
 * 缓动统一用一条接近 Material 标准的曲线（快出慢入）。之前各处混用
 * `FastOutSlowIn` / `LinearOutSlowIn` / 默认 `spring`，同一屏里两种缓动叠加时，
 * 一个元素已经停住、另一个还在漂，观感上像掉帧。
 */
object Motion {

    /** 内容出现。 */
    const val ENTER_MS = 160

    /** 容器形变（展开 / 收起 / 高度变化）。 */
    const val EMPHASIS_MS = 240

    /** 内容消失。 */
    const val EXIT_MS = 120

    /** 标准曲线：起步快、落点稳。用于绝大多数位移与淡入。 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 形变曲线：两端都慢一点，用于圆角、尺寸这类「结构」属性。 */
    val Shape: Easing = FastOutSlowInEasing

    /** 退场曲线：直接往下走，不拖尾。 */
    val Exit: Easing = FastOutLinearInEasing

    /** 进入时略微过冲一点（仅用于需要「落地感」的小元素，例如动作条）。 */
    val Settle: Easing = LinearOutSlowInEasing

    fun <T> enter() = tween<T>(durationMillis = ENTER_MS, easing = Standard)

    fun <T> emphasis() = tween<T>(durationMillis = EMPHASIS_MS, easing = Shape)

    fun <T> exit() = tween<T>(durationMillis = EXIT_MS, easing = Exit)

    /**
     * 容器长高（展开）。
     *
     * 与 [emphasis] 同一条曲线、同一档时长：展开和圆角、位移是同一次形变的几个侧面，
     * 分成两条曲线就会出现「边框已经变圆、内容还在长」的错位。
     */
    fun <T> grow() = tween<T>(durationMillis = EMPHASIS_MS, easing = Standard)

    /** 容器变矮（收起）：退场比进场快一档，与 [exit] 同理。 */
    fun <T> shrink() = tween<T>(durationMillis = EXIT_MS, easing = Exit)
}

/**
 * 高度变化走同一条形变曲线。
 *
 * `animateContentSize()` 默认的 spring 会和旁边的圆角动画各走各的：圆角先到位，内容还在长，
 * 于是中间那 200ms 里看起来像「在呼吸」。改成与 [Motion.emphasis] 同一条曲线后，
 * 高度与圆角是同一次运动的两半。
 */
fun Modifier.motionContentSize(): Modifier =
    animateContentSize(animationSpec = Motion.emphasis())

/** 内容出现：淡入 + 从下往上 1/8 个自身高度。 */
fun contentEnter(): EnterTransition =
    fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it / 8 }

/** 内容消失：淡出 + 往下 1/8。 */
fun contentExit(): ExitTransition =
    fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { it / 8 }

/** 小元素出现：淡入 + 从 92% 放大。动作条、浮动按钮用它，不用位移。 */
fun popEnter(): EnterTransition =
    fadeIn(Motion.enter()) + scaleIn(Motion.enter(), initialScale = 0.92f)

/** 小元素消失。 */
fun popExit(): ExitTransition =
    fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = 0.94f)
