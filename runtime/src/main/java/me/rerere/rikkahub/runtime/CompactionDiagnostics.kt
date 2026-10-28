package me.rerere.rikkahub.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * A record of what a compaction actually did.
 *
 * Compaction is the one place where the app spends a model call to *throw information away*. When
 * it misbehaves the symptom is diffuse — the model forgets a constraint, or the same summary is
 * rebuilt every turn — and the only current evidence is a free-text log line at the end of a
 * successful run. Nothing records the cases that matter most: the compaction that was *skipped*
 * because the boundary did not advance, or the one that *failed* and left the context untouched.
 *
 * Codex's harness keeps a four-part record for exactly this reason (`strategy / trigger / phase /
 * status` in its compaction analytics). This is the same shape at Moxw's scale: an event, a stable
 * rendering, and a small in-memory history that a debug screen (or a bug report) can read.
 *
 * Deliberately **not** persisted: the durable conversation already stores the summary itself, and a
 * compaction event describes one attempt, not a durable fact. Process death loses the history,
 * which is the right trade for not adding a table and a migration.
 */
enum class CompactionTrigger { AUTO, MANUAL }

enum class CompactionStatus {
    /** A new summary was stored. */
    CREATED,

    /** The boundary did not advance: the existing compaction was kept and nothing was spent. */
    NO_OP,

    /** The request failed; the context is unchanged. */
    FAILED,
}

data class CompactionEvent(
    val conversationId: String,
    val trigger: CompactionTrigger,
    val status: CompactionStatus,
    /** Estimated context tokens before the attempt; null when it could not be measured. */
    val tokensBefore: Int? = null,
    /** Estimated context tokens after the attempt; null for [CompactionStatus.FAILED]. */
    val tokensAfter: Int? = null,
    val summaryChars: Int = 0,
    val modelId: String? = null,
    val atMs: Long = 0L,
) {
    /** Tokens the compaction removed, or null when either side was not measured. */
    val reclaimedTokens: Int?
        get() = if (tokensBefore != null && tokensAfter != null) {
            (tokensBefore - tokensAfter).coerceAtLeast(0)
        } else {
            null
        }

    /**
     * One stable `key=value` line. Stable because it is meant to be grepped and pasted into a bug
     * report: the fields keep their names and order, and unknown values render as `-` rather than
     * disappearing (a missing `reclaimed` field would read as "zero").
     */
    fun render(): String = buildString {
        append("compaction")
        append(" conv=").append(conversationId)
        append(" trigger=").append(trigger.name.lowercase())
        append(" status=").append(status.name.lowercase())
        append(" tokens=").append(tokensBefore?.toString() ?: "-")
        append("->").append(tokensAfter?.toString() ?: "-")
        append(" reclaimed=").append(reclaimedTokens?.toString() ?: "-")
        append(" summary_chars=").append(summaryChars)
        append(" model=").append(modelId ?: "-")
    }
}

/** Process-scoped per-conversation history, newest last. In-memory only — see [CompactionEvent]. */
object CompactionDiagnostics {

    /** Keep it small: this is for "what just happened", not for analysis. */
    const val MAX_PER_CONVERSATION: Int = 10

    private val byConversation = ConcurrentHashMap<String, ArrayDeque<CompactionEvent>>()

    fun record(event: CompactionEvent) {
        val history = byConversation.getOrPut(event.conversationId) { ArrayDeque() }
        synchronized(history) {
            history.addLast(event)
            while (history.size > MAX_PER_CONVERSATION) history.removeFirst()
        }
    }

    fun recent(conversationId: String): List<CompactionEvent> {
        val history = byConversation[conversationId] ?: return emptyList()
        return synchronized(history) { history.toList() }
    }

    fun clear(conversationId: String) {
        byConversation.remove(conversationId)
    }
}
