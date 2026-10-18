package me.rerere.rikkahub.service.run

import me.rerere.rikkahub.data.agentrun.RunResumePolicy
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * P5 — which ledger run is currently driving a conversation.
 *
 * The generation loop checkpoints per conversation (that is the coordinate it has), but
 * a checkpoint is only useful if it can be tied back to an `agent_runs` row. RunService
 * binds the pair for the lifetime of a run; ChatService reads it when it writes a
 * breakpoint, and falls back to the synthetic `chat:<conversationId>` id when no
 * autonomous run owns the conversation.
 *
 * In-memory by design: a process death clears it, and that is exactly when the
 * persisted checkpoint (not this map) is what recovery uses.
 */
object ConversationRunRegistry {

    private data class Binding(val runId: String, val kind: String)

    private val bindings = ConcurrentHashMap<Uuid, Binding>()

    fun bind(conversationId: Uuid, runId: String, kind: String) {
        bindings[conversationId] = Binding(runId, kind)
    }

    fun unbind(conversationId: Uuid) {
        bindings.remove(conversationId)
    }

    fun runIdFor(conversationId: Uuid): String? = bindings[conversationId]?.runId

    fun kindFor(conversationId: Uuid): String? = bindings[conversationId]?.kind

    /** Checkpoint id for a conversation: the owning run, or the synthetic chat scope. */
    fun checkpointScope(conversationId: Uuid): Pair<String, String> {
        val binding = bindings[conversationId]
        return if (binding != null) {
            binding.runId to binding.kind
        } else {
            "chat:$conversationId" to RunResumePolicy.CHAT_KIND
        }
    }
}
