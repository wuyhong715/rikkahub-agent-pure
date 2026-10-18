package me.rerere.rikkahub.runtime

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * LoopTypes — the value objects that decouple the generation loop (:runtime) from the
 * app's data layer (:app).
 *
 * P4 of the harness re-architecture: GenerationLoop moves into :runtime, an
 * Android-free module. The loop must not reference :app's `Settings` / `Assistant`
 * aggregates (their type graphs pull in DataStore, Room and UI concerns), so the
 * caller projects exactly what the loop reads into these two types.
 *
 * The field lists are not abstractions invented here — they are the precise set of
 * fields GenerationLoop was observed to read (P4 recon, 2026-10-18): 4 Settings fields
 * (one flattened from NetworkSetting into the two watchdog ints) and 16 Assistant
 * fields. If the loop later needs more, add the field HERE (compile error forces the
 * projection in :app to stay honest).
 */

/** The 5 settings fields the loop reads, flattened. Projected from :app's Settings by
 *  the caller (see ChatService). */
data class LoopSettings(
    /** Provider list the loop resolves `model.findProvider` against. */
    val providers: List<ProviderSetting>,
    /** Stream retry budget per model request. */
    val responseStreamMaxRetries: Int = 5,
    /** Whether to parse textual tool calls from the model output. */
    val parseTextToolCalls: Boolean = true,
    /** Stream watchdog: max seconds to wait for the FIRST chunk. */
    val streamFirstOutputTimeoutSeconds: Int = 120,
    /** Stream watchdog: max seconds of silence between chunks. */
    val streamStallTimeoutSeconds: Int = 120,
) {
    companion object {
        /** Hard ceiling for the stream watchdog, mirrors NetworkSetting.MAX_WATCHDOG_SECONDS. */
        const val MAX_WATCHDOG_SECONDS = 600
    }
}

/** The 16 assistant fields the loop reads, projected from :app's Assistant. */
data class LoopAssistant(
    val id: Uuid,
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val streamOutput: Boolean = true,
    val contextMessageLimit: Int = 0,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false,
    val enableRecentChatsReference: Boolean = false,
    val enableToolExecutionRetry: Boolean = false,
    val allowConversationSystemPrompt: Boolean = false,
    val toolResultMaxTokens: Int? = null,
)

/** One memory entry as the loop's memory prompt needs it. Projected from
 *  :app's AssistantMemory (whose id is an Int). */
data class LoopMemory(
    val id: Int,
    val content: String,
)

/** One recent-conversation entry for the "Recent Chats" prompt block. The date is
 *  pre-formatted by :app (its util layer owns the localisation). */
data class RecentChatEntry(
    val title: String,
    val lastChatDate: String,
)
