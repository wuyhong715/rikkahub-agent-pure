package me.rerere.rikkahub.data.ai.prompts

/**
 * Default instruction for the context-compaction summary.
 *
 * This is a **handoff** instruction, not a "summarize this" instruction: what the model is really
 * being asked is "brief the agent that will replace you". Two consequences shape the text below.
 *
 * 1. It must not ask for tool commands and outputs to be copied. Moxw already retains a
 *    deterministic ledger of completed tool calls in the compacted request
 *    (`ContextCompactionPlanner.mandatoryToolExecutionDigest`), prepended ahead of this prompt by
 *    `ChatService`. Asking the model to re-transcribe them duplicates that ledger, spends the
 *    target budget twice, and — because prose drifts — is the less faithful of the two copies. So
 *    this prompt asks for what the ledger cannot carry: what each attempt *concluded*, which
 *    approach proved wrong, and what remains unknown.
 * 2. It must say what is *not* worth keeping. Long compaction requests are usually full of
 *    intermediate attempts and already-resolved errors; carrying them forward costs tokens and
 *    invites the next turn to redo settled work.
 *
 * The "what you still don't know" and "forward plan" sections are the highest-value part and were
 * missing entirely: at compaction time the model understands the task better than it ever will
 * again, and the next turn has no way to recover the questions that were never written down.
 *
 * Placeholders (`{target_tokens}`, `{locale}`, `{additional_context}`, `{content}`) are substituted
 * by `String.applyPlaceholders` in `ChatService`. Keep all four present so a user who edits the
 * template still sees the documented set.
 */
internal val DEFAULT_COMPRESS_PROMPT = """
    You are writing a handoff summary. The conversation below is about to be dropped from the
    context of a model that must continue the same task; this summary is what it will have left,
    alongside the user's recent messages and a separate ledger of completed tool calls.

    Write in the language the conversation is using (the device locale is {locale}; when the two
    disagree, follow the conversation). Do not impose rigid section headings — let the shape follow
    the work. Target approximately {target_tokens} tokens.

    Preserve, in your own words:

    - What the latest request actually asks for: your reading of its intent, and any ambiguity you
      have already resolved — not a transcription of the request. If several requests are in play,
      say which one governs the next move.
    - The constraints currently in force: user preferences, project rules, environment and tooling
      limits. Keep decisions you have settled (what you chose, and why) separate from questions still
      open, so the next turn neither reopens a settled choice nor treats an undecided point as
      decided.
    - What was actually done and what it established. Completed tool calls and their results are
      retained separately, so do not re-transcribe commands or copy their output: state what each
      attempt concluded, which file paths, identifiers or values matter, and which approach turned
      out to be wrong. Keep only the final working version of any code; drop intermediate attempts
      and already-resolved errors.
    - What you still do not know: files or paths referenced but never read, APIs assumed but not
      confirmed, questions the user has not answered. Name these gaps explicitly so the next turn
      goes and checks them instead of assuming.
    - The forward plan — and this is the moment to invest in it, because you understand this task
      better right now than anyone will later. Write the next concrete steps, the files to touch,
      and how the result should be verified.

    {additional_context}

    Output the summary directly, with no preamble and no meta-commentary, starting with a clear
    indicator that this is a summary of earlier conversation.

    If you are summarizing only one part of a longer conversation that will be merged with other
    parts later, leave out the global forward plan and the open questions — the merging pass adds
    those — and keep this part factual and self-contained.

    <conversation>
    {content}
    </conversation>
""".trimIndent()

/**
 * The defaults this app has shipped before. Kept verbatim so [CompressPromptDefaults.upgrade] can
 * tell "never touched" from "deliberately edited" without a migration version: the prompt is
 * persisted on every settings write, so changing only [DEFAULT_COMPRESS_PROMPT] would leave every
 * existing install on the old text forever.
 */
internal val LEGACY_COMPRESS_PROMPT = """
    You are a conversation compression assistant. Compress the following conversation into a concise summary.

    Requirements:
    1. Preserve key facts, decisions, and important context that would be needed to continue the conversation
    2. Keep the summary in the same language as the original conversation
    3. Target approximately {target_tokens} tokens
    4. Output the summary directly without any explanations or meta-commentary
    5. Format the summary as context information that can be used to continue the conversation
    6. Use {locale} language
    7. Start the output with a clear indicator that this is a summary (e.g., "[Summary of previous conversation]" or equivalent in the target language)
    8. Preserve completed tool calls and their outcomes. Include tool names, meaningful arguments,
       factual results or errors, and important file paths, URLs, IDs, and state changes. Do not
       replace them with a vague statement that tools were used.

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()

/**
 * Same contract as `DefaultAssistantPrompt`: an untouched (or blank) built-in prompt upgrades, a
 * user-edited one is left exactly as written. Whitespace-insensitive, so an install that merely
 * reformatted the shipped text still upgrades.
 */
internal object CompressPromptDefaults {

    private val SHIPPED: List<String> = listOf(LEGACY_COMPRESS_PROMPT)

    fun isUntouched(prompt: String): Boolean {
        if (prompt.isBlank()) return true
        val normalized = normalize(prompt)
        return SHIPPED.any { normalize(it) == normalized }
    }

    fun upgrade(prompt: String): String = if (isUntouched(prompt)) DEFAULT_COMPRESS_PROMPT else prompt

    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
