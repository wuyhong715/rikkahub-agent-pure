package me.rerere.rikkahub.runtime

import kotlin.uuid.Uuid
import me.rerere.rikkahub.runtime.tools.MemoryKind

/**
 * LoopGateways — the narrow interfaces through which the generation loop reaches the
 * app's repositories and services (P4). :app implements each gateway; :runtime code
 * never sees Room, DataStore or repositories.
 *
 * Each interface covers exactly the call sites GenerationLoop had before the
 * extraction (P4 recon, 2026-10-18) — no speculative surface.
 */

/** Memory writes issued by the loop's built-in memory tools (3 call sites:
 *  add / updateContent / delete). Implemented by :app's MemoryRepository.
 *  Note the id types mirror the repo exactly: memory rows are Int-keyed, while
 *  assistantId travels as a String. */
interface LoopMemoryGateway {
    /**
     * Stores one memory, or explains why it refused.
     *
     * The result is a [LoopMemoryWrite] rather than a stored row because the write is *gated*:
     * a duplicate, a blank record or a full bank must come back to the model as a correction it
     * can act on ("edit #12"), not as an exception the tool layer would turn into a generic
     * failure. Kind rides along because the injection rules order and expire records by it.
     */
    suspend fun addMemory(assistantId: String, content: String, kind: MemoryKind): LoopMemoryWrite

    /**
     * Rewrites record [id], which must be owned by [ownerId].
     *
     * The owner travels with the call because the id comes from the model: a memory id is a small
     * auto-increment integer, so an unscoped update lets any assistant rewrite another assistant's
     * memory (or the global bank's) by guessing. Unknown and foreign ids are reported identically.
     */
    suspend fun updateContent(id: Int, content: String, ownerId: String): LoopMemory

    /** Deletes record [id], which must be owned by [ownerId]. See [updateContent]. */
    suspend fun deleteMemory(id: Int, ownerId: String)
}

/**
 * Outcome of a gated memory write.
 *
 * [Rejected] is not an error path: it is the gate doing its job, and the tool renders it as a
 * normal JSON result so the model can retry as an edit in the same turn.
 */
sealed interface LoopMemoryWrite {
    data class Stored(val memory: LoopMemory) : LoopMemoryWrite

    data class Rejected(
        /** Wire reason: "blank" | "duplicate" | "capacity". */
        val reason: String,
        /** Model-facing instruction telling it what to do instead. */
        val hint: String,
        /** The record it should edit, when the rejection was a duplicate. */
        val existing: LoopMemory? = null,
    ) : LoopMemoryWrite
}

/**
 * Memory scope sentinel for the assistant-agnostic global memory bank. Mirrors
 * :app's `MemoryRepository.GLOBAL_MEMORY_ID` — the literal is the Seam's contract,
 * so it is duplicated here rather than imported (that would pull :app into :runtime).
 */
const val GLOBAL_MEMORY_ID = "__global__"

/** Recent-chats prompt source: the loop's buildRecentChatsPrompt needs the N most
 *  recent conversations of an assistant (title + pre-formatted date). Implemented in
 *  :app over ConversationRepository. */
interface LoopRecentChatsProvider {
    suspend fun recentChats(assistantId: Uuid, limit: Int): List<RecentChatEntry>
}

/** Generation log sink — the loop emits an AILogging.Generation record per model
 *  request (parameters + messages + provider + stream flag). :app's AILoggingManager
 *  implements this; the developer page reads from there as before. */
interface LoopLogSink {
    fun addLog(log: AILogging)
}
