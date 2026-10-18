package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.runtime.LoopAssistant
import me.rerere.rikkahub.runtime.LoopMemory
import me.rerere.rikkahub.runtime.LoopMemoryGateway
import me.rerere.rikkahub.runtime.LoopRecentChatsProvider
import me.rerere.rikkahub.runtime.LoopSettings
import me.rerere.rikkahub.runtime.RecentChatEntry
import me.rerere.rikkahub.runtime.util.toLocalDate
import kotlin.uuid.Uuid

/**
 * Adapters from the :runtime loop gateways to the app's real repositories (P4).
 * Thin pass-throughs — no behaviour of their own.
 */

class MemoryGatewayAdapter(
    private val memoryRepo: MemoryRepository,
) : LoopMemoryGateway {
    override suspend fun addMemory(assistantId: String, content: String): LoopMemory =
        memoryRepo.addMemory(assistantId, content).toLoopMemory()

    override suspend fun updateContent(id: Int, content: String): LoopMemory =
        memoryRepo.updateContent(id, content).toLoopMemory()

    override suspend fun deleteMemory(id: Int) {
        memoryRepo.deleteMemory(id)
    }
}

class RecentChatsProviderAdapter(
    private val conversationRepo: ConversationRepository,
) : LoopRecentChatsProvider {
    override suspend fun recentChats(assistantId: Uuid, limit: Int): List<RecentChatEntry> =
        conversationRepo.getRecentConversations(assistantId, limit).map { conversation ->
            RecentChatEntry(
                title = conversation.title,
                lastChatDate = conversation.updateAt.toLocalDate(),
            )
        }
}

/**
 * Projections :app → :runtime (P4). One direction only: the loop reads these value
 * objects and never sees `Settings` / `Assistant` / `AssistantMemory` themselves.
 * Field-for-field hand-offs — the loop's KDoc lists exactly which fields it reads.
 */

fun Settings.toLoopSettings() = LoopSettings(
    providers = providers,
    responseStreamMaxRetries = responseStreamMaxRetries,
    parseTextToolCalls = parseTextToolCalls,
    streamFirstOutputTimeoutSeconds = networkSetting.streamFirstOutputTimeoutSeconds,
    streamStallTimeoutSeconds = networkSetting.streamStallTimeoutSeconds,
)

fun Assistant.toLoopAssistant() = LoopAssistant(
    id = id,
    systemPrompt = systemPrompt,
    temperature = temperature,
    topP = topP,
    maxTokens = maxTokens,
    streamOutput = streamOutput,
    contextMessageLimit = contextMessageLimit,
    reasoningLevel = reasoningLevel,
    customHeaders = customHeaders,
    customBodies = customBodies,
    enableMemory = enableMemory,
    useGlobalMemory = useGlobalMemory,
    enableRecentChatsReference = enableRecentChatsReference,
    enableToolExecutionRetry = enableToolExecutionRetry,
    allowConversationSystemPrompt = allowConversationSystemPrompt,
    toolResultMaxTokens = toolResultMaxTokens,
)

fun AssistantMemory.toLoopMemory() = LoopMemory(
    id = id,
    content = content,
)
