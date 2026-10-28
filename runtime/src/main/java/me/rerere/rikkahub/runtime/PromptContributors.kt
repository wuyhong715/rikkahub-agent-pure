package me.rerere.rikkahub.runtime

/**
 * Where the app-level prompt blocks belong, stated once.
 *
 * Two blocks currently reach the model through a side channel rather than the section registry:
 * `AppEnvironmentPrompt` is appended to the system message by a transformer after the prompt has
 * been built, and `RuntimePolicyPrompt` arrives as the builder's `policyPrompt` argument. Both
 * work, but neither has an explicit order, so "where does the environment block sit relative to
 * memory?" is answered by the plumbing rather than by policy — and a block appended after the
 * assembled prompt cannot sit inside the cached prefix even when it is stable.
 *
 * This object is the migration target: same text, explicit name/order/stability, one table to read
 * when someone wonders what the model sees first. [appEnvironment] is [PromptStability.STABLE]
 * because that block is built from stable facts only (see its own design note: no version, no SDK
 * level, no device model); a capability change such as a newly granted shell privilege changes it
 * and costs one cache miss, which is the correct trade for the model knowing what it can do.
 */
object PromptContributors {

    /** Device layout and capability block: shared folders, library, semantic backend, privilege. */
    const val APP_ENVIRONMENT = "app-environment"

    /** The device owner's hard limits (approval mode, dispatch profile, step ceiling). */
    const val RUNTIME_POLICY = "runtime-policy"

    /**
     * Placement inside the stable block: after the assistant prompt and app rules, before the tool
     * documentation — the model reads what the app *is* before it reads what the tools *do*.
     */
    const val APP_ENVIRONMENT_ORDER = 700

    /** Volatile tail, after memory and chats: the last thing the model reads before acting. */
    const val RUNTIME_POLICY_ORDER = 500

    fun appEnvironment(text: String?): PromptSection? =
        PromptAssembler.optional(APP_ENVIRONMENT, APP_ENVIRONMENT_ORDER, PromptStability.STABLE, text)

    fun runtimePolicy(text: String?): PromptSection? =
        PromptAssembler.optional(RUNTIME_POLICY, RUNTIME_POLICY_ORDER, PromptStability.VOLATILE, text)

    /** Both, in one call, dropping whichever is blank. */
    fun appBlocks(appEnvironment: String?, runtimePolicy: String?): List<PromptSection> =
        listOfNotNull(appEnvironment(appEnvironment), runtimePolicy(runtimePolicy))
}
