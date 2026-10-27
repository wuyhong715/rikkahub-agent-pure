package me.rerere.rikkahub.ui.pages.workbench

import me.rerere.rikkahub.data.mission.MissionStatus

/**
 * 任务状态的“视觉语气”。
 *
 * 改这个文件的原因：状态颜色原本直接绑在 Material 的容器色上——ACTIVE 用 primaryContainer、
 * BLOCKED 用 errorContainer、REVIEW 用 tertiaryContainer、DONE 用 **secondaryContainer**、
 * ARCHIVED 用 surfaceVariant。问题有三个：
 *
 * 1. `secondary` 在很多主题里是灰绿/灰紫的“第二品牌色”，和“已完成”没有语义关系。
 *    用户看到绿色药丸和蓝色“进行中”并排，反而会以为绿色是成功、蓝色是过程。
 * 2. 容器色（*Container）是低饱和的浅色，拿它画左侧色条和进度条，浅色主题下几乎
 *    看不清进度走了多少。
 * 3. 同一张卡片里左侧色条、状态药丸、进度条三处都喝同一桶容器色，既重复又都太淡。
 *
 * 现在拆成两层：状态 → 语气（本文件，纯函数，可单测）；语气 → 颜色（`missionStatusPalette`，
 * 一个地方映射到主题色）。深浅色与动态取色仍由 `MaterialTheme.colorScheme` 决定，
 * 只是每种语气分别拿到**实心色**（accent）和**容器色**（container）两个档位。
 */
internal enum class MissionStatusTone {

    /** 正在推进 —— 主色，表示“活的”。 */
    PROGRESS,

    /** 卡住了，等人处理 —— 错误色，同一屏里唯一允许出现的红。 */
    ATTENTION,

    /** 跑完了，等用户验收 —— 第三色，明显区别于主色，但不报警。 */
    REVIEW,

    /** 已完成 —— 中性偏正向，不做强调，避免和主操作抢注意力。 */
    COMPLETE,

    /** 归档 —— 比 COMPLETE 更弱，退到背景里。 */
    MUTED,
}

/**
 * 状态 → 语气。五个状态对应五个语气，一一对应、没有两个状态共用一个语气，
 * 这样“运行中”和“待验收”永远不会长成同一个颜色。
 */
internal fun statusTone(status: MissionStatus): MissionStatusTone = when (status) {
    MissionStatus.ACTIVE -> MissionStatusTone.PROGRESS
    MissionStatus.BLOCKED -> MissionStatusTone.ATTENTION
    MissionStatus.REVIEW -> MissionStatusTone.REVIEW
    MissionStatus.DONE -> MissionStatusTone.COMPLETE
    MissionStatus.ARCHIVED -> MissionStatusTone.MUTED
}

/**
 * 验收进度比例，0f..1f。
 *
 * 返回 null 表示“这条任务没有验收项”，调用方据此**不画进度条**——之前是
 * `checked.size / criteria.size`，一旦在空列表上调用就是 NaN，进度条会画成一条
 * 全满的实心线，看起来像“全部通过”。
 */
internal object MissionProgress {

    fun fraction(done: Int, total: Int): Float? {
        if (total <= 0) return null
        return (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
}
