/**
 * 动效刻度 —— 与 Android 端 `ui/theme/Motion.kt` 同一套。
 *
 * 只有三档时长，对应三个用途：
 * - `enter`（160ms）：内容出现。必须比手指抬起的感知延迟短。
 * - `emphasis`（240ms）：容器形变（面板展开、输入条长高、抽屉滑入）。
 * - `exit`（120ms）：内容消失——比出现更快，消失不需要被看清。
 *
 * 缓动统一到一条类 Material 标准的曲线（快出慢入）。之前各处混用默认 ease 与
 * `ease-in-out`，同屏里两种曲线叠加时，一个元素已经停住、另一个还在漂，观感像掉帧。
 *
 * CSS 变量（`--motion-enter` 等）定义在 `app/app.css` 的 `:root` 里，
 * 这样 Tailwind 任意值写法 `duration-[var(--motion-emphasis)]` 也能用同一套刻度。
 */
export const motion = {
  /** 内容出现。 */
  enter: 160,
  /** 容器形变。 */
  emphasis: 240,
  /** 内容消失。 */
  exit: 120,
  /** 标准曲线：起步快、落点稳。 */
  ease: "cubic-bezier(0.2, 0, 0, 1)",
} as const;

/**
 * 供 Tailwind 任意值使用的 CSS 变量。
 *
 * 用变量而不是字面量的原因：动画时长是跨组件的一致性约束，写在组件里就会出现
 * 「这个面板 200ms、那个面板 300ms」，而没人会去数它们。
 */
export const motionVar = {
  enter: "var(--motion-enter)",
  emphasis: "var(--motion-emphasis)",
  exit: "var(--motion-exit)",
  ease: "var(--motion-ease)",
} as const;
