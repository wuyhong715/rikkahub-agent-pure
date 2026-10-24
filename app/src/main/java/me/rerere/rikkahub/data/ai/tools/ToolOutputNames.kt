package me.rerere.rikkahub.data.ai.tools

/**
 * Naming rules for spilled tool output.
 *
 * The name is derived from a tool-call id supplied by the model/provider.
 * `File(dir, "$toolCallId.txt")` with a hostile id (`../../shared_prefs/x`) writes
 * outside tool_outputs/. One shared sanitiser keeps writer and reader agreed.
 */
object ToolOutputNames {

    const val EXTENSION: String = ".txt"
    const val MAX_NAME_CHARS: Int = 64

    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    fun fileNameFor(toolCallId: String): String {
        val sanitized = UNSAFE.replace(toolCallId, "_")
            .trimStart('.')
            .take(MAX_NAME_CHARS - EXTENSION.length)
            .trimEnd('.')
        val stem = sanitized.ifBlank { "output" }
        return stem + EXTENSION
    }
}
