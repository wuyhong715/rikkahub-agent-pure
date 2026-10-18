package me.rerere.rikkahub.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import me.rerere.rikkahub.Brand
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.runtime.LoopHostBridge
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "AndroidLoopHostBridge"

/**
 * :app implementation of [LoopHostBridge] (P4) — every Android effect the generation
 * loop needs, implemented against the real device: R.string status text, the floating
 * agent overlay, the post-automation auto-return, and the tool-output spill directory.
 *
 * The behaviour is lifted verbatim from the pre-P4 GenerationLoop (same strings, same
 * safety checks, same intents) — only the location changed.
 */
class AndroidLoopHostBridge(
    private val context: Context,
) : LoopHostBridge {

    override fun continuingStatus(number: Int, max: Int): String =
        context.getString(R.string.chat_page_continuing, number, max)

    override fun retryingStatus(retryNumber: Long, maxRetries: Int, failure: Throwable): String =
        context.getString(R.string.chat_page_retrying, retryNumber, maxRetries, retryFailureReason(failure))

    override fun streamWaitingStatus(idleSeconds: Long): String =
        context.getString(R.string.chat_stream_waiting_first_output, idleSeconds)

    override fun streamStalledStatus(idleSeconds: Long): String =
        context.getString(R.string.chat_stream_stalled, idleSeconds)

    override fun showOverlay() {
        AgentOverlay.show(context)
    }

    override fun hideOverlay() {
        AgentOverlay.hide(context)
    }

    /**
     * Auto-return to the host app after a screen-automation turn. Safety first: if the
     * user has switched to a third app (neither the automation destination nor this app),
     * we do NOT yank them back — we toast an explanation instead.
     */
    override fun autoReturnToHost(lastDestination: String?) {
        val currentForeground = RikkaAccessibilityService.instance
            ?.rootInActiveWindow?.packageName?.toString()

        val userSwitchedAway = lastDestination != null &&
            currentForeground != null &&
            currentForeground != lastDestination &&
            currentForeground != context.packageName

        if (userSwitchedAway) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context.applicationContext,
                    "${Brand.NAME}: skipped auto-return because you switched apps. (Safety feature)",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // startActivity throws ActivityNotFoundException / SecurityException — both
            // Exception. Catching Throwable would also swallow JVM errors; let those pass.
            Log.w(TAG, "auto-return launch failed", e)
        }
    }

    override fun spillToolOutput(toolCallId: String, fullText: String): String? = runCatching {
        val fileName = "$toolCallId.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)
        fileName
    }.getOrNull()

    /**
     * Headless runs have no approval channel, so a tool that would need one is refused with a
     * structured envelope instead of hanging. The policy (which tools, what the envelope says)
     * stays in :app — HeadlessToolApprovalPolicy + HeadlessConversations own it.
     */
    override fun headlessRefusal(toolName: String, conversationId: Uuid?): String? =
        me.rerere.rikkahub.data.ai.tools.HeadlessToolApprovalPolicy.refusalEnvelopeFor(
            toolName = toolName,
            headless = conversationId?.let { id ->
                me.rerere.rikkahub.data.ai.tools.HeadlessConversations.shouldAutoApprove(id)
            } ?: false,
        )

    override val isDebugBuild: Boolean
        get() = BuildConfig.DEBUG
}

/** The user-facing failure reason in a retry status line (ported verbatim from the
 *  pre-P4 GenerationLoop): first non-blank message in the cause chain, whitespace
 *  collapsed, capped at 240 chars; the JVM class name when no message exists. */
private fun retryFailureReason(failure: Throwable): String =
    generateSequence(failure) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?.replace(Regex("\\s+"), " ")
        ?.take(240)
        ?: failure.javaClass.simpleName
