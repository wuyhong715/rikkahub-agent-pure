package me.rerere.rikkahub.runtime

import kotlin.uuid.Uuid

/**
 * LoopHostBridge — every Android effect the generation loop needs, behind one
 * interface (P4 of the harness re-architecture).
 *
 * :runtime is an Android-free module by construction: it never imports Context,
 * Toast, R.string, BuildConfig or the accessibility service. Anything the loop needs
 * from the host device flows through this bridge, implemented in :app and injected
 * into GenerationLoop's constructor.
 *
 * Implementations must be safe to call from any coroutine context the loop runs on
 * (Dispatchers.Default/IO); UI-thread hopping is the implementation's problem.
 */
interface LoopHostBridge {

    // ------------------------------------------------------------------
    // Status strings (R.string lives in :app; the loop shows these in the
    // processing-status pill while working)
    // ------------------------------------------------------------------

    /** "Continuing (n/max)" — shown when the loop chains another tool step. */
    fun continuingStatus(number: Int, max: Int): String

    /** "Retrying (n/max): <cause>" — shown while a model request is being retried. */
    fun retryingStatus(retryNumber: Long, maxRetries: Int, failure: Throwable): String

    /** "Waiting for first output (Ns)" — stream watchdog notice, no chunk yet. */
    fun streamWaitingStatus(idleSeconds: Long): String

    /** "Stalled (Ns)" — stream watchdog notice, chunks stopped mid-stream. */
    fun streamStalledStatus(idleSeconds: Long): String

    // ------------------------------------------------------------------
    // Screen-automation effects
    // ------------------------------------------------------------------

    /** Show the floating agent overlay (screen automation is about to start). */
    fun showOverlay()

    /** Hide the floating agent overlay. */
    fun hideOverlay()

    /**
     * Auto-return to the host app after a screen-automation turn ends. The loop has
     * already decided the turn involved navigation+automation (AgentTurnTracker); the
     * implementation performs the safety check (did the user switch away?), the toast
     * when skipping, and the relaunch intent. [lastDestination] is the package the
     * agent last drove, null when unknown.
     */
    fun autoReturnToHost(lastDestination: String?)

    // ------------------------------------------------------------------
    // Tool-output spill
    // ------------------------------------------------------------------

    /**
     * Persist an oversized tool result to the host's tool-output directory and return
     * the file name (relative, e.g. "<toolCallId>.txt"), or null when the write fails.
     * The loop degrades to plain truncation on null — a spill failure must never fail
     * the tool call itself.
     */
    fun spillToolOutput(toolCallId: String, fullText: String): String?

    /**
     * The structured refusal for a tool a headless run may not call (no approval channel
     * exists), or null when the tool is fine. Implemented in :app over
     * HeadlessToolApprovalPolicy + HeadlessConversations — both carry app-side policy and
     * preference state, so the loop asks instead of importing.
     */
    fun headlessRefusal(toolName: String, conversationId: Uuid?): String?

    /** Whether this is a debug build (gates extra diagnostics). */
    val isDebugBuild: Boolean
}
