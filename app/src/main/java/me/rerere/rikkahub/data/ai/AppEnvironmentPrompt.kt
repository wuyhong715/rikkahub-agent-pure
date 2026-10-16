package me.rerere.rikkahub.data.ai

/**
 * Renders the `<app_environment>` block that
 * [me.rerere.rikkahub.data.ai.transformers.AppEnvironmentTransformer] appends to the system
 * prompt of every turn, so the model knows where this app's on-device resources live instead of
 * guessing paths: the user-visible shared folders it should deliver files into, the app-private
 * storage it should NOT deliver into, the knowledge-base and file-library directories (when the
 * assistant has them configured), whether semantic search has an embedding model behind it, and
 * what privilege the Shizuku shell currently runs with.
 *
 * Design rule (拍板 2026-10-17): the block carries only ACTION-RELEVANT and STABLE facts.
 * Anything volatile or behaviourally irrelevant — app version, versionCode, Android SDK level,
 * device model — is deliberately absent: the model's behaviour never branches on them, and
 * keeping them out keeps the block byte-identical across releases and devices. What stays is
 * what changes what the model DOES: paths it can hand to file tools, capabilities that decide
 * whether a tool call will work, and the privilege the shell runs with.
 *
 * The two workspace-path lines are conditional on purpose: they are emitted exactly when the
 * matching tools (`memory_*` / `library_search`) are registered, so the prompt never advertises
 * a place the model cannot act on. The conditions are mirrored from
 * `ChatService.createColdMemoryToolsIfConfigured` / `createLibraryToolsIfConfigured`; if those
 * gates change, the transformer's collection code must change with them.
 *
 * Pure — no Android, no I/O. The transformer collects every value and hands it in pre-resolved,
 * so this file is what the unit tests pin down.
 */
object AppEnvironmentPrompt {

    /** Which backend would serve semantic search. [NONE] means no embedding model is set up. */
    enum class SemanticBackend { NONE, LOCAL, CLOUD }

    /**
     * Coarse shell privilege, mirroring `shizuku.PrivilegeLevel` without this pure file
     * depending on the Shizuku package.
     */
    enum class ShellPrivilege { UNKNOWN, SHELL, ROOT }

    data class Info(
        /** Brand short name, used in shared-folder paths: "Moxw". */
        val appName: String,
        /** `context.filesDir.absolutePath`. */
        val privateFilesDir: String,
        /** "/workspace/<dir>" when cold memory is fully configured; null omits the line. */
        val coldMemoryLabel: String? = null,
        /** "/workspace/<dir>" when the file library is fully configured; null omits the line. */
        val libraryLabel: String? = null,
        val semanticBackend: SemanticBackend = SemanticBackend.NONE,
        /** Model file name (LOCAL) or display name (CLOUD); null renders "auto". */
        val semanticModelLabel: String? = null,
        val shellPrivilege: ShellPrivilege = ShellPrivilege.UNKNOWN,
    )

    fun render(info: Info): String = buildString {
        appendLine("<app_environment>")
        appendLine(
            "- User-visible shared folders — files written here appear in the user's Files app; " +
                "deliver user-facing output here:"
        )
        appendLine("  - /sdcard/Documents/${info.appName}/ — documents, reports, exports")
        appendLine("  - /sdcard/Pictures/${info.appName}/ — images, screenshots, photos")
        appendLine("  - /sdcard/Download/${info.appName}/ — downloads")
        appendLine(
            "- App-private storage: ${info.privateFilesDir} — invisible to the user; " +
                "never place deliverables here."
        )
        if (!info.coldMemoryLabel.isNullOrBlank()) {
            appendLine(
                "- Knowledge base (cold memory): ${info.coldMemoryLabel} — persistent Markdown documents " +
                    "across conversations; list with memory_index, read with memory_read, write with " +
                    "memory_write, search with memory_search."
            )
        }
        if (!info.libraryLabel.isNullOrBlank()) {
            appendLine(
                "- File library: ${info.libraryLabel} — files placed here are indexed and searchable via " +
                    "library_search; when the user wants you to \"know\" a document, ask them to put it here."
            )
        }
        when (info.semanticBackend) {
            SemanticBackend.NONE -> appendLine(
                "- Semantic search: NOT ready — no embedding model is set up, so memory_search / " +
                    "library_search / tool_search / conversation_search will report themselves unavailable; " +
                    "ask the user to install an embedding model in Settings."
            )

            SemanticBackend.LOCAL -> appendLine(
                "- Semantic search: ready (local embedding model " +
                    "\"${sanitize(info.semanticModelLabel ?: "auto")}\")."
            )

            SemanticBackend.CLOUD -> appendLine(
                "- Semantic search: ready (cloud embedding model " +
                    "\"${sanitize(info.semanticModelLabel ?: "auto")}\")."
            )
        }
        when (info.shellPrivilege) {
            ShellPrivilege.ROOT -> appendLine(
                "- Shell privilege: Shizuku runs as root (uid 0) — shell tools have full device access."
            )

            ShellPrivilege.SHELL -> appendLine(
                "- Shell privilege: Shizuku runs as shell (uid 2000, adb level); root_exec requires a " +
                    "root broker and refuses otherwise."
            )

            ShellPrivilege.UNKNOWN -> appendLine(
                "- Shell privilege: Shizuku is not running — shizuku_exec / root_exec will fail until " +
                    "the user starts Shizuku."
            )
        }
        append("</app_environment>")
    }.trim()

    /**
     * Model file names and cloud display names are user/provider-controlled: strip control
     * characters so one cannot forge new prompt lines.
     */
    private fun sanitize(raw: String): String =
        raw.filterNot { it.isISOControl() }.trim().take(MAX_FIELD_CHARS).ifBlank { "unknown" }

    private const val MAX_FIELD_CHARS = 80
}
