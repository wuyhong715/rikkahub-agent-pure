package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext

/**
 * [limitContext] that cannot eat the compacted summary at the head of the request.
 *
 * A compacted view is `[summary] + rawTail`. The summary is one synthetic user message
 * standing in for every message before the tail. The message-count limit is positional and
 * will drop that summary while keeping a verbose tail — worse than not compacting.
 *
 * Leading synthetic messages are pinned; the raw tail is limited to `limit - pinned`
 * (at least one). With no leading synthetic message this is exactly [limitContext].
 */
internal fun List<UIMessage>.limitContextPreservingLeadingSynthetic(limit: Int): List<UIMessage> {
    if (limit <= 0 || size <= limit) return this

    val pinned = indexOfFirst { !it.isSynthetic }.let { if (it < 0) size else it }
    if (pinned == 0) return limitContext(limit)
    if (pinned >= size) return this

    val tailLimit = (limit - pinned).coerceAtLeast(1)
    return subList(0, pinned) + subList(pinned, size).limitContext(tailLimit)
}
