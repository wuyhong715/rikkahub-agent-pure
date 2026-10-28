package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ColdMemoryRules
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.vector.ColdMemorySearchHit
import me.rerere.rikkahub.data.vector.MemoryIndexCoordinator

private const val TAG = "AutoRecall"

/** A recall that has not answered by then is not worth the first token. */
private const val RECALL_TIMEOUT_MS = 900L

/** Fetched before de-duplication: a document usually contributes several chunks. */
private const val RECALL_FETCH_LIMIT = 6

private const val RECALL_MAX_ITEMS = 3
private const val RECALL_SUMMARY_CHARS = 140

/** A message shorter than this ("ok", "继续") is not a query; recall would be noise. */
private const val RECALL_MIN_QUERY_CHARS = 6

/** Embedding models have limits; a pasted document must not become the query. */
private const val RECALL_MAX_QUERY_CHARS = 2_000

/**
 * T-16 — proactive recall.
 *
 * Search used to be entirely model-driven: it had to think "there might be a note about this"
 * before `memory_search` was ever called. The step that gets skipped is the thinking, not the
 * tool. This transformer runs the same semantic search every turn against the last user
 * message and, when something matches, appends a three-line menu of file names to the system
 * prompt. The passages themselves are not injected — the model still has to open what it needs,
 * which keeps the block small and keeps `memory_read` the single source of the actual text.
 *
 * Design rules:
 * - One search per distinct query; the result (including "nothing found") is cached for the
 *   life of the ChatService, so retries and continuation rounds do not re-embed.
 * - Best-effort: no embedding model, a timeout, or a failed search degrades to no injection.
 * - Nothing is injected when the assistant has no knowledge base, or the switch is off.
 */
class AutoRecallTransformer(
    private val memoryIndex: MemoryIndexCoordinator,
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {

    private var cachedKey: String? = null
    private var cachedBlock: String? = null

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.settings.enableAutoRecall) return messages

        val assistant = ctx.assistant
        if (!assistant.coldMemoryEnabled) return messages
        // Mirrors AppEnvironmentTransformer's gate: an unbound or deleted workspace injects
        // nothing, so the prompt never advertises notes the turn cannot read.
        val workspaceId = ctx.missionWorkspaceId ?: assistant.workspaceId?.toString() ?: return messages
        val dir = ColdMemoryRules.normalizeDir(assistant.coldMemoryDir) ?: return messages
        if (workspaceRepository.getById(workspaceId) == null) return messages

        val query = lastUserQuery(messages) ?: return messages
        val key = "$workspaceId|$dir|$query"

        val block = if (key == cachedKey) {
            cachedBlock
        } else {
            val computed = try {
                withTimeoutOrNull(RECALL_TIMEOUT_MS) {
                    memoryIndex.search(workspaceId, dir, query, RECALL_FETCH_LIMIT)
                }?.takeIf { it.available }?.let { renderRecall(it.hits) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "recall failed; skipping injection", e)
                null
            }
            cachedKey = key
            cachedBlock = computed
            computed
        } ?: return messages

        if (block.isBlank()) return messages
        return injectIntoSystem(messages, block)
    }

    /** The last thing the user actually said; tool output and images are not a query. */
    private fun lastUserQuery(messages: List<UIMessage>): String? {
        val last = messages.lastOrNull { it.role == MessageRole.USER } ?: return null
        val text = last.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        if (text.length < RECALL_MIN_QUERY_CHARS) return null
        return text.take(RECALL_MAX_QUERY_CHARS)
    }

    private fun injectIntoSystem(messages: List<UIMessage>, block: String): List<UIMessage> {
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendRecall(block)
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(block).copy(isSynthetic = true)) + messages
        }
    }
}

/**
 * One line per document, highest scoring chunk first. Pure, so the shape is unit-tested
 * without a vector index.
 */
internal fun renderRecall(
    hits: List<ColdMemorySearchHit>,
    maxItems: Int = RECALL_MAX_ITEMS,
    maxChars: Int = RECALL_SUMMARY_CHARS,
): String {
    if (hits.isEmpty() || maxItems <= 0) return ""

    val best = LinkedHashMap<String, ColdMemorySearchHit>()
    hits.sortedByDescending { it.score }.forEach { hit ->
        if (!best.containsKey(hit.file)) best[hit.file] = hit
    }

    val top = best.values.take(maxItems)
    if (top.isEmpty()) return ""

    return buildString {
        appendLine("<recall>")
        appendLine("[Possibly relevant cold memory — open a file only when this task needs it]")
        top.forEach { hit ->
            append("- ")
            append(hit.file)
            append(" — ")
            append(summarize(hit.text, maxChars))
            append('\n')
        }
        append("</recall>")
    }
}

private fun summarize(text: String, max: Int): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max) + "…"
}

private fun UIMessage.appendRecall(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + "\n\n" + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
