package me.rerere.rikkahub.runtime

/**
 * Ordered, stability-tagged contributions to the system prompt.
 *
 * Until now the system prompt was a fixed-arity function: `buildSections()` took eight named
 * parameters and decided the order in its own body. That works while every contributor is known in
 * advance, but it has two costs that show up as soon as a feature wants to say something:
 *
 * - **Adding a section means editing the builder.** A feature cannot contribute its own rules
 *   without touching a file that also decides caching; the safest routine change is the one nobody
 *   wants to make.
 * - **The cache boundary is implicit.** "Stable" is expressed as "which parameter you were passed
 *   to", so nothing stops a volatile value (a device path, a live counter) from being appended to
 *   the stable block and silently invalidating the provider's prompt cache every turn.
 *
 * This turns both into data: every section declares its own [order] and [stability], and
 * [PromptAssembler] sorts and joins. Ordering inside a block is deterministic down to the name, so
 * two runs with the same inputs are byte-identical — which is exactly the property prompt caching
 * depends on.
 *
 * Pure: no Android, no I/O, no clock. `SystemPromptBuilder` delegates here so the existing prompt
 * stays byte-identical while new contributors gain a place to register.
 */
enum class PromptStability {
    /**
     * Byte-identical turn to turn. This is the cached prefix: the assistant prompt, app rules, tool
     * documentation. Nothing device-specific, timed, or conversation-specific may go here.
     */
    STABLE,

    /** Changes between turns (memory, recent chats, per-request addenda). Sits after the prefix. */
    VOLATILE,
}

/** One block of the system prompt. Blank [text] means "this contributor had nothing to say". */
data class PromptSection(
    /** Stable identity, used for ordering ties and for diagnosing a surprising prompt. */
    val name: String,
    /** Ascending placement inside [stability]; ties break by name. */
    val order: Int,
    val stability: PromptStability,
    val text: String,
)

/**
 * Renders a section list into the `(stable, volatile)` pair the request builders consume.
 *
 * Sections are joined with a single newline and the block is trimmed, matching the format the
 * hand-written builder produced — the equivalence is pinned by `SystemPromptBuilderTest`.
 */
object PromptAssembler {

    fun assemble(sections: List<PromptSection>): Pair<String, String> =
        render(sections, PromptStability.STABLE) to render(sections, PromptStability.VOLATILE)

    private fun render(sections: List<PromptSection>, stability: PromptStability): String =
        sections
            .asSequence()
            .filter { it.stability == stability && it.text.isNotBlank() }
            .sortedWith(compareBy({ it.order }, { it.name }))
            .joinToString("\n") { it.text }
            .trim()

    /**
     * Convenience for a single contributor that may have nothing to append: returns a section with
     * [text] as-is, or null when it is blank so the caller can skip it entirely.
     */
    fun optional(name: String, order: Int, stability: PromptStability, text: String?): PromptSection? =
        if (text.isNullOrBlank()) null else PromptSection(name, order, stability, text)
}
