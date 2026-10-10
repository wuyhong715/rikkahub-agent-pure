package me.rerere.rikkahub.data.vector

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid

private const val TAG = "ConversationIndex"

/**
 * Keeps the history index up to date, in the background.
 *
 * Two triggers, because history changes in two different ways:
 *
 *  - opening a conversation sweeps the whole history, so anything written since the last sweep is
 *    picked up. Debounced like the other indexes, and budgeted, so the first sweep over a year of
 *    chat is a background round rather than a stall.
 *  - finishing a turn syncs **the conversation just talked in**, on a much shorter debounce, so
 *    what was said a minute ago is already recallable. This is the half that makes the index useful
 *    *within* a conversation instead of only across them.
 *
 * The second trigger is why the source's `sync` takes a scope. A per-turn sync reads one
 * conversation; without the scope it would read every other conversation as deleted, and the first
 * turn after a long chat would wipe the history out of the index.
 *
 * A round is planned from metadata only - conversation ids, message ids, text lengths - so a walk
 * over the entire history costs bounded memory and no model calls. Only the messages that fit the
 * budget are read in full and embedded.
 */
class ConversationIndexCoordinator(
    private val scope: CoroutineScope,
    private val embeddings: EmbeddingService,
    private val source: ConversationVectorSource,
    private val conversationRepository: ConversationRepository,
) {

    data class Status(
        val running: Boolean = false,
        val lastReport: Report? = null,
        val lastError: String? = null,
        val lastRunAtMs: Long = 0L,
    )

    data class Report(
        /** Messages the walk found that are worth indexing. */
        val candidates: Int,
        val read: Int,
        val skipped: Int,
        val deferred: Int,
        val tooBig: Int,
        val indexedNow: Int,
        val unchanged: Int,
        val indexedTotal: Int,
        val chunks: Int,
        val embeddedChunks: Int,
        /** True when this run was limited to one conversation. */
        val scopedToConversation: Boolean = false,
        val reason: String? = null,
    ) {
        val hasMore: Boolean get() = deferred > 0
    }

    /** What the tool answers with: hits already resolved to a conversation. */
    data class Response(
        val available: Boolean,
        val results: List<ConversationSearchResult> = emptyList(),
        val indexedMessages: Int = 0,
        val stale: Boolean = false,
        val note: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val lastAttemptAtMs = mutableMapOf<String, Long>()

    /** Sweeps the whole history, unless one ran within [minIntervalMs]. */
    fun requestSync(minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS): Boolean {
        val key = "all"
        val now = System.currentTimeMillis()
        val previous = lastAttemptAtMs[key] ?: 0L
        if (now - previous < minIntervalMs) {
            Log.d(TAG, "skipping history sweep: one ran ${(now - previous) / 1000}s ago")
            return false
        }
        lastAttemptAtMs[key] = now
        scope.launch { runCatching { syncNow() }.onFailure { Log.d(TAG, "sweep failed", it) } }
        return true
    }

    /**
     * Syncs the conversation a turn just happened in.
     *
     * Returns false when the debounce skipped it, which is not a failure: a turn that writes six
     * messages should cost one round, not six.
     */
    fun requestSyncOf(conversationId: Uuid, minIntervalMs: Long = DEFAULT_TURN_INTERVAL_MS): Boolean {
        val key = "turn:$conversationId"
        val now = System.currentTimeMillis()
        val previous = lastAttemptAtMs[key] ?: 0L
        if (now - previous < minIntervalMs) return false
        lastAttemptAtMs[key] = now
        scope.launch {
            runCatching { syncConversation(conversationId) }
                .onFailure { Log.d(TAG, "turn sync failed", it) }
        }
        return true
    }

    /** One round over the whole history. Returns null when there is nothing to do or no model. */
    suspend fun syncNow(): Report? = runReport(scopedToConversation = null)

    /** One round over a single conversation: what the turn-complete trigger calls. */
    suspend fun syncConversation(conversationId: Uuid): Report? =
        runReport(scopedToConversation = conversationId.toString())

    private suspend fun runReport(scopedToConversation: String?): Report? {
        if (embeddings.ensureLoaded() == null) {
            _status.value = Status(
                running = false,
                lastError = "no embedding model is installed",
                lastRunAtMs = System.currentTimeMillis(),
            )
            return null
        }

        _status.value = _status.value.copy(running = true, lastError = null)
        return try {
            val scoped = scopedToConversation != null
            val ids = if (scoped) listOf(scopedToConversation!!) else conversationRepository.getAllConversationIds()
            val candidates = collectCandidates(ids)
            val round = ConversationSearchRules.planRound(
                candidates = candidates,
                indexed = source.indexedPaths(),
            )

            // Read only what the round chose, and only once per conversation: the walk that picked
            // the candidates deliberately never held their text.
            var read = 0
            var skipped = 0
            val docs = mutableListOf<ConversationVectorSource.Doc>()
            round.take.groupBy { it.conversationId }.forEach { (conversationId, group) ->
                val conversation = loadConversation(conversationId) ?: return@forEach
                val wanted = group.map { it.messageId }.toSet()
                conversation.messageNodes.forEach { node ->
                    node.messages.forEach { message ->
                        val messageId = message.id.toString()
                        if (messageId !in wanted) return@forEach
                        val text = messageText(message)
                        if (!ConversationSearchRules.isIndexable(text)) {
                            skipped++
                            return@forEach
                        }
                        docs += ConversationVectorSource.Doc(conversationId, messageId, text)
                        read++
                    }
                }
            }

            val synced = source.sync(
                docs = docs,
                // Everything the walk saw - including the messages it did not read this round and
                // the ones too short to index - stays. Only a message that vanished from the
                // history is forgotten.
                seen = candidates.map { it.docKey }.toSet() + round.take.map { it.docKey },
                nowMs = System.currentTimeMillis(),
                scope = scopedToConversation,
            )

            val report = Report(
                candidates = candidates.size,
                read = read,
                skipped = skipped,
                deferred = round.deferred,
                tooBig = round.tooBig.size,
                indexedNow = synced.indexed,
                unchanged = synced.unchanged,
                indexedTotal = source.indexedMessages(),
                chunks = synced.chunks,
                embeddedChunks = synced.embeddedChunks,
                scopedToConversation = scoped,
                reason = synced.reason,
            )
            _status.value = Status(
                running = false,
                lastReport = report,
                lastRunAtMs = System.currentTimeMillis(),
            )
            Log.d(
                TAG,
                "history index: ${report.read} read, ${report.indexedNow} indexed, " +
                    "${report.embeddedChunks} embedded, ${report.deferred} deferred, " +
                    "${report.indexedTotal} in the index",
            )
            report
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = Status(
                running = false,
                lastError = e.message ?: e::class.java.simpleName,
                lastRunAtMs = System.currentTimeMillis(),
            )
            Log.d(TAG, "history index failed", e)
            null
        }
    }

    /**
     * Metadata for every indexable message of [conversationIds].
     *
     * Deliberately returns lengths rather than text: a history can hold hundreds of thousands of
     * messages, and a round needs their identity and their cost, not their prose. The chosen ones
     * are read afterwards.
     */
    private suspend fun collectCandidates(
        conversationIds: List<String>,
    ): List<ConversationSearchRules.Candidate> {
        val candidates = mutableListOf<ConversationSearchRules.Candidate>()
        for (id in conversationIds) {
            val conversation = loadConversation(id) ?: continue
            conversation.messageNodes.forEach { node ->
                node.messages.forEach { message ->
                    val text = messageText(message)
                    if (text.length < ConversationSearchRules.MIN_MESSAGE_CHARS) return@forEach
                    candidates += ConversationSearchRules.Candidate(
                        conversationId = id,
                        messageId = message.id.toString(),
                        chars = text.length,
                    )
                }
            }
        }
        return candidates
    }

    private suspend fun loadConversation(conversationId: String): Conversation? = try {
        conversationRepository.getConversationById(Uuid.parse(conversationId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "cannot load conversation '$conversationId': ${e.message}")
        null
    }

    /**
     * The part of a message worth embedding: its text, capped like the keyword index capped it.
     *
     * Only [UIMessagePart.Text] counts. Tool calls and their results are excluded on purpose - they
     * are the bulk of a long history and the least likely thing anyone searches for - and so are
     * reasoning parts, which were never meant to be quoted back.
     */
    private fun messageText(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .take(ConversationSearchRules.MAX_MESSAGE_CHARS)

    /**
     * Semantic history search, resolved into what the tool answers with.
     *
     * The title and the date come from the conversation rather than from the index, one lookup per
     * hit: both change over time and neither is worth re-embedding a message for.
     */
    suspend fun search(query: String, limit: Int): Response {
        val outcome = source.search(query, limit)
        if (!outcome.available) {
            return Response(available = false, note = outcome.note)
        }
        val resolved = outcome.hits.map { hit ->
            val conversation = loadConversation(hit.conversationId)
            ConversationSearchResult(
                conversationId = hit.conversationId,
                messageId = hit.messageId,
                title = conversation?.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                date = dateOf(conversation),
                score = hit.score,
                text = hit.text,
            )
        }
        return Response(
            available = true,
            results = resolved,
            indexedMessages = outcome.indexedMessages,
            stale = outcome.stale,
            note = outcome.note,
        )
    }

    private fun dateOf(conversation: Conversation?): String {
        val millis = conversation?.updateAt?.toEpochMilli() ?: return ""
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }

    companion object {
        /** Five minutes: long enough that flicking between conversations is free. */
        const val DEFAULT_MIN_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * Half a minute. The turn that just finished should be searchable by the time the user
         * asks about it, without paying a model call per message in a burst.
         */
        const val DEFAULT_TURN_INTERVAL_MS = 30 * 1000L
    }
}
