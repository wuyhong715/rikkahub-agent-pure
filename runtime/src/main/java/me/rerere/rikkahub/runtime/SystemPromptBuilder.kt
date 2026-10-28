package me.rerere.rikkahub.runtime

/**
 * Single place for assembling the system prompt that is sent to every provider.
 *
 * Callers provide pre-rendered sections so the ordering and formatting live in one spot
 * rather than being reimplemented in GenerationHandler and the provider adapters.
 *
 * Ordering is **stable-first**: the assistant prompt and tool prompts (byte-identical turn
 * to turn) come first, then the volatile sections (memory, recent chats, per-call
 * addendum) that change between turns. This lets prompt caching work: auto-caching
 * providers (OpenAI/DeepSeek/Grok/Gemini) reuse the stable byte-prefix, and OpenRouter's
 * explicit cache_control breakpoint is placed at the stable/volatile boundary (see
 * ChatCompletionsAPI). Volatile text in the prefix busts the cache every turn, which is
 * what happened before when memory was enabled.
 *
 * The eight well-known contributors below are now expressed as [PromptSection]s and rendered by
 * [PromptAssembler], so a feature can add its own block through [extraSections] without editing
 * this file — and any contributor that mislabels a volatile value as [PromptStability.STABLE] is
 * visible in one place instead of hidden inside string concatenation. The rendered bytes are
 * unchanged: `SystemPromptBuilderTest` pins the equivalence.
 */
class SystemPromptBuilder {

    /**
     * Returns the system prompt split into `(stable, volatile)`.
     * - stable: assistant prompt + app injections + tool cost guidance + tool prompts.
     * - volatile: memory + recent chats + per-call addendum + runtime policy.
     * Either may be blank.
     *
     * [systemInjectionPrompt] is the app's own layer (see [SystemInjections]); it sits after
     * the user's assistant prompt so a recency-reading model gives it more weight, and it is
     * byte-stable turn to turn, so it stays inside the cached prefix.
     *
     * [extraSections] is the registration point for feature-owned blocks (missions, sub-agents,
     * skills). They are ordered by their own [PromptSection.order] alongside the built-ins.
     */
    fun buildSections(
        assistantPrompt: String,
        systemInjectionPrompt: String = "",
        appEnvironmentPrompt: String = "",
        memoryPrompt: String = "",
        factsPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        policyPrompt: String = "",
        extraSections: List<PromptSection> = emptyList(),
    ): Pair<String, String> = PromptAssembler.assemble(
        sections(
            assistantPrompt = assistantPrompt,
            systemInjectionPrompt = systemInjectionPrompt,
            appEnvironmentPrompt = appEnvironmentPrompt,
            memoryPrompt = memoryPrompt,
            factsPrompt = factsPrompt,
            recentChatsPrompt = recentChatsPrompt,
            toolPrompts = toolPrompts,
            systemAddendum = systemAddendum,
            policyPrompt = policyPrompt,
            extraSections = extraSections,
        )
    )

    /**
     * The same content as [buildSections], before rendering.
     *
     * Exposed so a feature can inspect or extend an assembly (add its block, drop one it owns)
     * without re-deriving the built-in ordering.
     */
    fun sections(
        assistantPrompt: String,
        systemInjectionPrompt: String = "",
        appEnvironmentPrompt: String = "",
        memoryPrompt: String = "",
        factsPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        policyPrompt: String = "",
        extraSections: List<PromptSection> = emptyList(),
    ): List<PromptSection> = buildList {
        add(PromptSection(ASSISTANT, 100, PromptStability.STABLE, assistantPrompt))
        add(PromptSection(APP_INJECTIONS, 200, PromptStability.STABLE, systemInjectionPrompt))
        // Blank by default: the environment block still arrives through its transformer until the
        // call site is switched over, and passing "" must keep today's bytes unchanged.
        PromptContributors.appEnvironment(appEnvironmentPrompt)?.let(::add)
        if (toolPrompts.isNotEmpty()) {
            add(PromptSection(TOOL_COST_GUIDANCE_SECTION, 900, PromptStability.STABLE, TOOL_COST_GUIDANCE))
            toolPrompts.forEachIndexed { index, toolPrompt ->
                add(PromptSection("tool:$index", 1_000 + index, PromptStability.STABLE, toolPrompt))
            }
        }
        add(PromptSection(MEMORY, 100, PromptStability.VOLATILE, memoryPrompt))
        add(PromptSection(FACTS, 200, PromptStability.VOLATILE, factsPrompt))
        add(PromptSection(RECENT_CHATS, 300, PromptStability.VOLATILE, recentChatsPrompt))
        add(PromptSection(ADDENDUM, 400, PromptStability.VOLATILE, systemAddendum.orEmpty()))
        add(PromptSection(POLICY, 500, PromptStability.VOLATILE, policyPrompt))
        addAll(extraSections)
    }

    /** Combined single-string prompt (stable then volatile), for callers/providers that do
     *  not split into cache blocks. */
    fun build(
        assistantPrompt: String,
        systemInjectionPrompt: String = "",
        appEnvironmentPrompt: String = "",
        memoryPrompt: String = "",
        factsPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        policyPrompt: String = "",
        extraSections: List<PromptSection> = emptyList(),
    ): String {
        val (stable, volatile) = buildSections(
            assistantPrompt = assistantPrompt,
            systemInjectionPrompt = systemInjectionPrompt,
            appEnvironmentPrompt = appEnvironmentPrompt,
            memoryPrompt = memoryPrompt,
            factsPrompt = factsPrompt,
            recentChatsPrompt = recentChatsPrompt,
            toolPrompts = toolPrompts,
            systemAddendum = systemAddendum,
            policyPrompt = policyPrompt,
            extraSections = extraSections,
        )
        return listOf(stable, volatile).filter { it.isNotBlank() }.joinToString("\n")
    }

    companion object {
        /**
         * Names are part of the diagnostics surface (and of ordering ties), so they are constants
         * rather than literals at the call site: a feature that wants to replace or remove the
         * built-in block needs a name it can match on.
         */
        const val ASSISTANT = "assistant"
        const val APP_INJECTIONS = "app-injections"
        const val TOOL_COST_GUIDANCE_SECTION = "tool-cost-guidance"
        const val MEMORY = "memory"
        const val FACTS = "facts"
        const val RECENT_CHATS = "recent-chats"
        const val ADDENDUM = "addendum"
        const val POLICY = "policy"

        /**
         * Appended ahead of the tool documentation when any tool exposes a prompt. Kept stable (it
         * never changes between turns), so it costs nothing on the cached prefix.
         */
        const val TOOL_COST_GUIDANCE: String =
            "Tool cost guidance: prefer low-cost text tools before expensive visual or broad tools. " +
                "Use read_window_tree/browser_get_text before screenshots when text is enough, and " +
                "avoid repeating high-cost tools unless the state likely changed."
    }
}
