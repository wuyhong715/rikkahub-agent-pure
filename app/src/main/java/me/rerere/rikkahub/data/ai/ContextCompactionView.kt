package me.rerere.rikkahub.data.ai

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.runtime.AnchorCandidate
import me.rerere.rikkahub.runtime.CompactionAnchors
import me.rerere.rikkahub.runtime.ContextCompactionPlanner
import me.rerere.rikkahub.runtime.tools.PrunableToolOutput
import me.rerere.rikkahub.runtime.tools.ToolOutputPruner
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid

data class CompactedMessageView(
    val messages: List<UIMessage>,
    val compaction: ConversationCompaction?,
    val rawTailStartIndex: Int,
    /** Present only for the request that created a new automatic compaction. */
    val newlyCreatedAutoCompaction: ConversationCompaction? = null,
)

object ContextCompactionView {

    /**
     * The user's messages from the region the summary is about to replace, chosen by
     * [CompactionAnchors] and rendered as a trailing part of the summary message.
     *
     * Rationale (Kimi Code CLI's compaction keeps user entries rather than summarizing them): a
     * summary is written under a token target and keeps what *it* judges important, so the user's
     * exact constraints are the first thing to be paraphrased away — and once paraphrased, the next
     * turn cannot tell a requirement existed. Tool output is already covered by the deterministic
     * digest (`ContextCompactionPlanner.mandatoryToolExecutionDigest`); this covers the other half.
     *
     * Bounded by [CompactionAnchors.DEFAULT_BUDGET_TOKENS]; blank or synthetic user messages are
     * skipped so a compaction of a compaction does not anchor the previous summary.
     */
    private fun userAnchors(conversation: Conversation, endExclusive: Int): String {
        if (endExclusive <= 0) return ""
        val candidates = conversation.currentMessages
            .take(endExclusive)
            .mapIndexedNotNull { index, message ->
                if (message.role != MessageRole.USER || message.isSynthetic) return@mapIndexedNotNull null
                val text = message.toText().trim()
                if (text.isBlank()) return@mapIndexedNotNull null
                AnchorCandidate(
                    index = index,
                    text = text,
                    tokens = ContextCompactionPlanner.estimateTokens(text),
                )
            }
        return CompactionAnchors.render(CompactionAnchors.select(candidates))
    }

    /**
     * Caps oversized tool results inside the raw tail.
     *
     * The tail is what survives a compaction verbatim, and in practice it is a handful of very
     * large results rather than many small ones. They are re-sent at full size on every subsequent
     * request until the next compaction — and they are usually why the tail sits just under the
     * trigger and crosses it again on the next tool call.
     *
     * Deterministic by design ([ToolOutputPruner.plan] uses a fixed cap and target, not a
     * per-turn budget): a size-dependent rule would re-render the same results differently as the
     * conversation grows, and every re-render is a new request prefix and a cache miss.
     *
     * Only text is replaced; images and other parts of the same result are kept, so a screenshot
     * attached to a shell result does not disappear along with its stdout.
     */
    private fun pruneTail(tail: List<UIMessage>): List<UIMessage> {
        if (tail.isEmpty()) return tail
        val candidates = ArrayList<PrunableToolOutput>()
        // (message index, part index, parts that are not text) per candidate, in the same order.
        val anchors = ArrayList<Triple<Int, Int, List<UIMessagePart>>>()
        tail.forEachIndexed msg@{ messageIndex, message ->
            message.parts.forEachIndexed part@{ partIndex, part ->
                if (part !is UIMessagePart.Tool) return@part
                val texts = part.output.filterIsInstance<UIMessagePart.Text>()
                if (texts.isEmpty()) return@part
                val joined = texts.joinToString("\n") { it.text }
                candidates += PrunableToolOutput(
                    index = candidates.size,
                    tokens = ContextCompactionPlanner.estimateTokens(joined),
                    text = joined,
                )
                anchors += Triple(
                    messageIndex,
                    partIndex,
                    part.output.filter { it !is UIMessagePart.Text },
                )
            }
        }
        val plan = ToolOutputPruner.plan(candidates)
        if (plan.isEmpty()) return tail
        val byCandidate = plan.associateBy { it.index }

        val rebuilt = tail.toMutableList()
        anchors.forEachIndexed { candidateIndex, (messageIndex, partIndex, nonTextParts) ->
            val pruned = byCandidate[candidateIndex] ?: return@forEachIndexed
            val message = rebuilt[messageIndex]
            val parts = message.parts.toMutableList()
            val tool = parts[partIndex] as? UIMessagePart.Tool ?: return@forEachIndexed
            parts[partIndex] = tool.copy(
                output = listOf(UIMessagePart.Text(pruned.replacement)) + nonTextParts,
            )
            rebuilt[messageIndex] = message.copy(parts = parts)
        }
        return rebuilt
    }

    /**
     * Builds the synthetic summary message with an identity stable across every request built
     * from the same [compaction], instead of the fresh random id / wall-clock createdAt that
     * `UIMessage.user()` defaults to. `TimeReminderTransformer` injects a time reminder before
     * the first user message of a request; with a per-request identity here, that first message
     * - and so the whole request prefix - would change on every request, defeating provider
     * prompt caching after compaction (RC3).
     */
    internal fun summaryMessage(
        compaction: ConversationCompaction,
        /**
         * The user's own words from the compacted region, rendered by [CompactionAnchors].
         * Carried as a second part of the summary message rather than as its own message: a
         * separate message would need an id, and a per-request id here changes the request
         * prefix on every turn (the same reason this message's identity is derived from the
         * compaction, see below).
         */
        anchors: String = "",
    ): UIMessage =
        UIMessage.user(compaction.summary).copy(
            parts = buildList {
                add(UIMessagePart.Text(compaction.summary))
                if (anchors.isNotBlank()) add(UIMessagePart.Text(anchors))
            },
            createdAt = compaction.createdAt.toKotlinInstant()
                .toLocalDateTime(TimeZone.currentSystemDefault()),
            // sourceEndNodeId is a MessageNode id, never a message id, so reusing it as this
            // synthetic message's id cannot collide with a real message's id.
            id = compaction.sourceEndNodeId,
            // Generated, never typed — so it must not be templated (TemplateTransformer
            // skips synthetic messages) and it must not be droppable by the message-count
            // limit, which is what this flag also tells
            // `limitContextPreservingLeadingSynthetic` to pin.
            isSynthetic = true,
        )

    fun build(
        conversation: Conversation,
        compaction: ConversationCompaction?,
    ): CompactedMessageView {
        if (compaction == null) return rawView(conversation)

        val sourceEndIndex = conversation.messageNodes.indexOfFirst {
            it.id == compaction.sourceEndNodeId
        }
        val resolvedTailStartIndex = compaction.tailStartNodeId?.let { tailStartNodeId ->
            conversation.messageNodes.indexOfFirst { it.id == tailStartNodeId }
        }
        // A non-null tailStartNodeId that no longer resolves (its node was deleted) falls back
        // to sourceEndIndex + 1, same as a null tailStartNodeId: the next node simply becomes
        // the tail start rather than invalidating the whole compaction.
        val tailStartIndex = resolvedTailStartIndex?.takeIf { it >= 0 } ?: (sourceEndIndex + 1)
        if (
            sourceEndIndex < 0 ||
            tailStartIndex != sourceEndIndex + 1 ||
            tailStartIndex !in 0..conversation.messageNodes.size
        ) {
            return rawView(conversation)
        }

        return CompactedMessageView(
            messages = ContextCompactionPresentation.stripDisplayTools(
                listOf(summaryMessage(compaction, userAnchors(conversation, tailStartIndex))) +
                    pruneTail(conversation.currentMessages.drop(tailStartIndex)),
            ),
            compaction = compaction,
            rawTailStartIndex = tailStartIndex,
        )
    }

    /**
     * Request view for regenerating at [endExclusive] (an index into [Conversation.messageNodes],
     * exclusive). Returns null when the stored compaction does not apply to that range, in which
     * case the caller keeps its raw behaviour.
     */
    fun buildForRange(
        conversation: Conversation,
        compaction: ConversationCompaction?,
        endExclusive: Int,
    ): CompactedMessageView? {
        val view = build(conversation, compaction)
        if (view.compaction == null) return null
        // rawTailStartIndex > endExclusive means the regenerated node sits inside the compacted
        // prefix; clearCompactionIfPrefixChanged already clears the compaction in that case, so
        // this check is only a defensive fallback.
        if (endExclusive !in view.rawTailStartIndex..conversation.messageNodes.size) return null

        return view.copy(
            messages = ContextCompactionPresentation.stripDisplayTools(
                // The regenerated request must carry the same prefix as a normal turn, anchors
                // included: a prefix that differs between the two is a cache miss and, worse, a
                // silently weaker context on exactly the turn the user asked to redo.
                listOf(summaryMessage(view.compaction, userAnchors(conversation, view.rawTailStartIndex))) +
                    conversation.currentMessages.subList(view.rawTailStartIndex, endExclusive),
            ),
        )
    }

    /**
     * Merges a generation result produced from a compacted context back into the complete
     * conversation. The synthetic summary is request-only and must never become a message node.
     */
    fun mergeGeneratedMessages(
        conversation: Conversation,
        view: CompactedMessageView,
        generatedMessages: List<UIMessage>,
    ): Conversation {
        require(view.compaction != null) { "A compacted view is required" }

        val inputSize = view.messages.size
        val nodes = conversation.messageNodes.toMutableList()
        // message id -> (nodeIndex, messageIndex), built once instead of the nested
        // `nodes.indexOfFirst { node -> node.messages.any { ... } }` scan this replaced (O(tail
        // x totalNodes) per streamed chunk, issue #109). First occurrence wins, matching
        // indexOfFirst. Kept in sync below whenever a node gains a message or a node is appended,
        // so a later generated message in this same call can still resolve against it.
        val locationByMessageId = HashMap<Uuid, Pair<Int, Int>>()
        nodes.forEachIndexed { nodeIndex, node ->
            node.messages.forEachIndexed { messageIndex, storedMessage ->
                locationByMessageId.putIfAbsent(storedMessage.id, nodeIndex to messageIndex)
            }
        }
        generatedMessages.forEachIndexed { index, message ->
            val location = locationByMessageId[message.id]
            if (location != null) {
                val (nodeIndex, messageIndex) = location
                val node = nodes[nodeIndex]
                val replacement = ContextCompactionPresentation.preserveDisplayTools(
                    previous = node.messages[messageIndex],
                    replacement = message,
                )
                if (node.messages[messageIndex] != replacement) {
                    nodes[nodeIndex] = node.copy(
                        messages = node.messages.toMutableList().apply {
                            this[messageIndex] = replacement
                        },
                    )
                }
            } else if (index >= inputSize) {
                // The generated list is [summary] + tail, so generated index i >= 1 corresponds
                // to node view.rawTailStartIndex + i - 1. On the normal (non-regenerate) path the
                // view's tail always runs to the end of the conversation, so this always lands
                // past the last node and falls through to the append below - byte-identical to
                // before this was made positional for the regenerate case.
                val boundaryNodeIndex = view.rawTailStartIndex + index - 1
                if (boundaryNodeIndex <= nodes.lastIndex) {
                    val node = nodes[boundaryNodeIndex]
                    val newMessages = node.messages + message
                    nodes[boundaryNodeIndex] = node.copy(
                        messages = newMessages,
                        selectIndex = newMessages.lastIndex,
                    )
                    locationByMessageId.putIfAbsent(message.id, boundaryNodeIndex to newMessages.lastIndex)
                } else {
                    nodes += message.toMessageNode()
                    locationByMessageId.putIfAbsent(message.id, nodes.lastIndex to 0)
                }
            }
        }

        return conversation.copy(messageNodes = nodes)
    }

    private fun rawView(conversation: Conversation) = CompactedMessageView(
        messages = ContextCompactionPresentation.stripDisplayTools(conversation.currentMessages),
        compaction = null,
        rawTailStartIndex = 0,
    )

    /** (node id, selected message id) for every node up to and including the compaction's source-end node, or null if that node is absent. */
    fun compactedPrefixSignature(
        nodes: List<MessageNode>,
        compaction: ConversationCompaction,
    ): List<Pair<Uuid, Uuid>>? {
        val end = nodes.indexOfFirst { it.id == compaction.sourceEndNodeId }
        if (end < 0) return null
        return nodes.take(end + 1).map { it.id to it.currentMessage.id }
    }

    /** True when [after] still carries the exact compacted prefix that [before] had. */
    fun compactedPrefixUnchanged(
        compaction: ConversationCompaction,
        before: List<MessageNode>,
        after: List<MessageNode>,
    ): Boolean {
        val expected = compactedPrefixSignature(before, compaction) ?: return false
        return compactedPrefixSignature(after, compaction) == expected
    }
}
