package me.rerere.rikkahub.runtime

import kotlin.uuid.Uuid

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
    suspend fun addMemory(assistantId: String, content: String): LoopMemory
    suspend fun updateContent(id: Int, content: String): LoopMemory
    suspend fun deleteMemory(id: Int)
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
