package me.rerere.rikkahub.subagent

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agentdef.AgentDefinition
import me.rerere.rikkahub.data.agentdef.AgentNamespace
import me.rerere.rikkahub.data.agentdef.toAssistant
import me.rerere.rikkahub.data.model.Assistant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * P2-04, extended by P2-06b — a sub-agent run's OWN tool surface, and the per-conversation
 * record of it.
 *
 * ## Why
 *
 * Until P2-04 a sub-agent conversation was created straight from its parent's assistant
 * (`SubAgentEngine.executeRun`: `Conversation.ofId(assistantId = parentAsstUuid)`), so the child's
 * surface was the parent's surface — "子 ⊆ 父". That is wrong in both directions:
 *
 *  - a specialist sub-agent often needs a tool the parent deliberately keeps off its own surface
 *    (a narrow child is the whole point of dispatching one), and
 *  - the only thing that should actually constrain a child is the headless **floor**
 *    ([SubAgentToolSurface]), because a headless run cannot ask the user anything — not an
 *    accident of which assistant happened to be the parent.
 *
 * [resolveChildAssistant] turns an expert [AgentDefinition] into a *derived* assistant
 * (parent + the expert's own surface), which is the one thing [ChatService]'s tool assembly needs
 * to build the child's list. Everything else the run needs — model, system prompt, memories,
 * compaction, permissions — keeps coming from the parent, exactly as before.
 *
 * ## P2-06b — the definition is the surface
 *
 * Before P2-06b this object carried its own three-field overlay (`localTools` /
 * `disabledLocalTools` / `mcpServers` copied off a `SubAgentProfile`). The expert library now
 * stores those same fields plus `skills` and the D9 `slug`, and
 * [me.rerere.rikkahub.data.agentdef.toAssistant] already knows how to overlay all of them, so the
 * overlay lives in exactly one place and the surface cannot drift from a dispatch that resolves
 * the same definition. `skills` and `slug` (which sets `coldMemoryDir` to `agents/<slug>/memory`)
 * therefore count as "this expert has a surface of its own" too: a definition that only names a
 * namespace still gets a derived child assistant, and every tool on it still passes through the
 * headless floor in [apply].
 *
 * ## Every sub-agent run has a record (G 批)
 *
 * Until G, a record was written only when the expert defined a surface or the parent had
 * `enableSubAgentToolSurface` on. With no record the child inherited the parent's COMPLETE tool
 * list — `ask_user`, the per-call-approval tools, everything — and headless auto-approve ran them
 * unattended. The engine now freezes unconditionally, so the floor always applies; the record also
 * carries whether this child may itself dispatch (nesting depth permitting).
 *
 * Conversations that were never frozen still get the **same `List<Tool>` instance** back from
 * [apply] — the identity contract is unchanged.
 */
object SubAgentSurface {

    // ---- child surface synthesis (pure) ------------------------------------------------

    /**
     * The assistant a sub-agent run's tool list should be assembled from, given its [definition].
     *
     * Returns `null` — "inherit the parent verbatim" — when there is nothing to override: no
     * definition at all, no parent assistant (a deleted parent), or a definition whose every
     * surface field is unset **and** whose parent has no skills to strip. That is the pre-P2-04
     * behaviour, and it is the answer for every expert that only picks a model / system prompt
     * when the parent assistant has no skills enabled.
     *
     * G 批第四片 — skills are the exception to "null means inherit": a child gets the expert's
     * declared skills, or none at all. So a definition that declares nothing still derives a
     * child when the parent has skills, purely to drop them.
     *
     * When at least one field is set, the parent is copied with each field taken from the
     * definition when it has an opinion and from the parent otherwise — the overlay itself lives in
     * [toAssistant], so this function and a dispatch that resolves the same definition can never
     * disagree about what "the expert's surface" is.
     *
     * The `id` is deliberately **not** changed (see [toAssistant]): the child stays "the same
     * assistant" for every context-aware tool (`assistantId` in the invocation context, memory
     * scoping, the recursion guard). Only the tool surface is the child's own.
     */
    fun resolveChildAssistant(parent: Assistant?, definition: AgentDefinition?): Assistant? {
        if (parent == null) return null
        if (definition == null) {
            // Ad-hoc dispatch (`subagent_dispatch` with no expert): there is no definition to
            // overlay, but the parent's skills must still not travel. A sub-agent that silently
            // carries the parent's whole skill library is not the narrow child the dispatcher asked
            // for — and the named-expert path already draws this line (an expert whose definition
            // has no `skills` gets none, see `toAssistant`). Nothing to strip → nothing to derive.
            return if (parent.enabledSkills.isEmpty()) null else parent.copy(enabledSkills = emptySet())
        }
        // G 批第四片 — 即使专家什么都没声明，只要父助手带着技能，也要派生出一个子助手：
        // 派生的唯一作用是把继承来的技能清空（见 toAssistant）。父助手本来就没有技能时返回
        // null，保持"没有差异就不派生"的旧行为。
        val stripsInheritedSkills = parent.enabledSkills.isNotEmpty() && definition.skills == null
        if (!hasOwnSurface(definition) && !stripsInheritedSkills) return null
        return definition.toAssistant(parent)
    }

    /**
     * True when [definition] defines any part of its own tool surface. A definition with every
     * surface field null is a "model + prompt" specialist and inherits the parent's whole surface.
     *
     * P2-06b: `skills` and `slug` are surface fields too. A definition that only names a private
     * namespace (D9) still gets a derived child assistant, so the namespace — and the headless
     * floor over the tools the child ends up with — is applied in P2-06b rather than waiting for
     * the UI card.
     */
    fun hasOwnSurface(definition: AgentDefinition?): Boolean =
        definition != null && (
            definition.localTools != null ||
                definition.disabledLocalTools != null ||
                definition.mcpServers != null ||
                definition.skills != null ||
                // A slug is only a surface when it normalises to a usable namespace: a blank or
                // otherwise unusable value leaves `coldMemoryDir` on the parent's, so claiming a
                // surface for it would derive a child that differs in nothing.
                AgentNamespace.normalizeSlug(definition.slug) != null
            )

    // ---- per-conversation frozen record -------------------------------------------------

    /**
     * The frozen surface of one sub-agent conversation.
     *
     * @param assistant the child's own surface assistant, or null to inherit the parent's
     *   assistant (the pre-P2-04 behaviour).
     * @param requested the names the dispatcher asked for, or null when it asked for no narrowing.
     *   Never empty: an empty array is treated as "not specified" so a model that sends
     *   `"tools": []` by accident still gets a working sub-agent instead of a tool-less one.
     */
    data class FrozenSurface(
        val assistant: Assistant?,
        val requested: Set<String>?,
        /**
         * G 批 — whether this child may itself dispatch (`subagent_dispatch` survives the floor).
         *
         * False by default: a child is one level deep, and whether it gets to spawn is decided by
         * the parent run's depth against [SubAgentNestingRules.capFor] at freeze time.
         */
        val allowSubagentDispatch: Boolean = false,
    )

    private val frozen = ConcurrentHashMap<Uuid, FrozenSurface>()

    /**
     * Freeze [conversationId] to [assistant] (the child's own surface, or null = inherit) and,
     * optionally, narrow it to [requested]. Called by the engine right after the sub-agent
     * conversation exists, and released in the same `finally` that unmarks it as headless.
     */
    fun freeze(
        conversationId: Uuid,
        assistant: Assistant? = null,
        requested: Collection<String>? = null,
        allowSubagentDispatch: Boolean = false,
    ): FrozenSurface {
        val surface = FrozenSurface(
            assistant = assistant,
            requested = requested?.filterNot { it.isBlank() }?.toSet()?.takeIf { it.isNotEmpty() },
            allowSubagentDispatch = allowSubagentDispatch,
        )
        frozen[conversationId] = surface
        return surface
    }

    /** Drop the record. After this [apply] is an identity again for this conversation. */
    fun release(conversationId: Uuid) {
        frozen.remove(conversationId)
    }

    /** The record for [conversationId], or null when the conversation was never frozen. */
    fun frozenFor(conversationId: Uuid): FrozenSurface? = frozen[conversationId]

    /**
     * The surface assistant this conversation's tool list must be assembled from, or null when the
     * conversation has no record (or its record inherits). `ChatService` substitutes this for the
     * resolved assistant at every tool-assembly site.
     */
    fun assistantFor(conversationId: Uuid): Assistant? = frozen[conversationId]?.assistant

    /**
     * Filter [tools] down to the frozen surface of [conversationId].
     *
     * The floor ([SubAgentToolSurface.isDenied]) is applied **per tool name here**, on the finished
     * list — so it covers the child's own local tools, its MCP tools, and the workspace / skill /
     * cold-memory / compaction tools that `ChatService` appends *outside* `LocalTools.getTools`
     * alike. A [`requested`][FrozenSurface.requested] allow-list narrows further, and a denial
     * always beats it.
     *
     * **Identity contract:** with no freeze record — i.e. every conversation in an install that
     * turns neither knob on, and every non-sub-agent conversation in one that does — the same
     * `List<Tool>` instance is returned, so the schemas handed to the model are byte-for-byte what
     * they were.
     */
    fun apply(conversationId: Uuid, tools: List<Tool>): List<Tool> {
        val surface = frozenFor(conversationId) ?: return tools
        val requested = surface.requested
        val kept = tools.filter { tool ->
            !SubAgentToolSurface.isDenied(tool.name, surface.allowSubagentDispatch) &&
                (requested == null || tool.name in requested)
        }
        // Nothing to remove -> hand back the original instance so identity stays observable
        // instead of silently producing an equal-but-new list.
        return if (kept.size == tools.size) tools else kept
    }

    /** Test hook: drop every record. Never called from production code. */
    fun clearAll() {
        frozen.clear()
    }
}
