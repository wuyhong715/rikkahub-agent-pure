package me.rerere.rikkahub.runtime.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * One page of a spilled tool result.
 *
 * [nextOffset] is where the following page starts; [exhausted] says there is no following
 * page. Callers page by character offset rather than by line because a truncated result is
 * not guaranteed to still be line-structured (a compressed JSON array is one line).
 */
data class ToolOutputSlice(
    val content: String,
    val totalChars: Int,
    val offset: Int,
    val nextOffset: Int,
    val exhausted: Boolean,
)

/**
 * Naming rules for spilled tool output — the one place that decides what a spill is called
 * on disk and which names a read request may resolve to.
 *
 * ## Why a rule object
 *
 * The name is derived from a tool-call id, and that id is **model- or provider-supplied**.
 * `File(dir, "$toolCallId.txt")` with a hostile id (`../../shared_prefs/x`, `a/b`) writes
 * outside the tool-output directory. Sanitising in one shared function means the writer and
 * the reader can never disagree about the mapping, and the reader can never be talked into a
 * name the writer would not have produced.
 *
 * The sanitiser is intentionally lossy: the only thing a reader needs is a handle it can
 * quote back, not a reversible encoding of the provider's id.
 */
object ToolOutputRules {

    /**
     * The name of the tool that reads a spilled result back.
     *
     * Shared with the generation loop, which must not truncate this tool's own answer: its whole
     * job is to hand back a page the model asked for, so spilling *it* produces a notice pointing
     * at yet another file — an endless ladder that never reaches the content.
     */
    const val READ_TOOL_NAME: String = "tool_output_read"

    const val EXTENSION: String = ".txt"

    /** Enough for a provider tool-call id plus the extension; anything longer is not one. */
    const val MAX_NAME_CHARS: Int = 64

    /** One page's hard ceiling, so a paging loop cannot reconstruct the whole file in one call. */
    const val MAX_SLICE_CHARS: Int = 20_000

    const val MIN_SLICE_CHARS: Int = 500

    private val UNSAFE = Regex("[^A-Za-z0-9._-]")
    private val SAFE_STEM = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}$")

    /**
     * The file name to spill [toolCallId] under. Deterministic for the same id, and always a
     * plain name inside the directory (no separators, no leading dot, bounded length).
     */
    fun fileNameFor(toolCallId: String): String {
        val sanitized = UNSAFE.replace(toolCallId, "_")
            .trimStart('.')
            .take(MAX_NAME_CHARS - EXTENSION.length)
            .trimEnd('.')
        val stem = sanitized.ifBlank { "output" }
        return stem + EXTENSION
    }

    /**
     * Resolves a name the model quoted back — `abc.txt`, `/tool_outputs/abc.txt` or bare
     * `abc` — into a spill file name, or null when it is not a name this app could have
     * written.
     *
     * Rejecting (rather than silently sanitising) an unusable request is deliberate: the
     * model that asked for `../foo` should be told the handle does not exist, not handed a
     * different file that happens to sanitise to the same thing.
     */
    fun resolveRequestedFile(request: String): String? {
        var trimmed = request.trim()
        if (trimmed.isEmpty()) return null
        // The one prefix a model plausibly quotes back from the notice or from `ls`.
        trimmed = trimmed.removePrefix("/tool_outputs/").removePrefix("tool_outputs/")
        if (trimmed.contains('/') || trimmed.contains('\\')) return null
        val stem = trimmed.removeSuffix(EXTENSION).removeSuffix(EXTENSION.uppercase())
        if (!SAFE_STEM.matches(stem)) return null
        return stem + EXTENSION
    }

    /** A bounded window of [text] starting at [offset], clamped into range. */
    fun slice(text: String, offset: Int, limitChars: Int): ToolOutputSlice {
        val total = text.length
        val start = offset.coerceAtLeast(0).coerceAtMost(total)
        val limit = limitChars.coerceIn(MIN_SLICE_CHARS, MAX_SLICE_CHARS)
        val end = (start + limit).coerceAtMost(total)
        return ToolOutputSlice(
            content = text.substring(start, end),
            totalChars = total,
            offset = start,
            nextOffset = end,
            exhausted = end >= total,
        )
    }
}

/**
 * `tool_output_read` — reads back a tool result the loop spilled to disk.
 *
 * ## Why a tool and not a shell hint
 *
 * Until now the only way back to a spilled result was `cat /tool_outputs/<id>.txt`, which
 * requires a READY workspace shell. Most conversations have none, so truncating without it
 * would have been a one-way door. This tool is the other side of the budget: the loop may
 * cut a result *because* this exists.
 *
 * It is read-only, takes no approval, and can only address files inside the app's own
 * tool-output directory (see [ToolOutputRules]) — the worst a confused model can do is read
 * a spill it already caused.
 */
fun buildToolOutputReadTool(
    read: suspend (fileName: String, offset: Int, limitChars: Int) -> ToolOutputSlice?,
): List<Tool> = listOf(
    Tool(
        name = ToolOutputRules.READ_TOOL_NAME,
        description = """
            Read the full text of a tool result that was truncated to fit the context budget.

            When a tool result is cut down, its message says how much was dropped and the exact
            file it was saved as. Call this with that file name to read the rest, in pages.
            Prefer it over guessing: re-running the command is slower, costs the same tokens,
            and may have side effects.

            - Offset is a character offset; start at 0 and follow `next_offset` until `has_more`
              is false.
            - A page is at most ${ToolOutputRules.MAX_SLICE_CHARS} characters. Ask for a smaller
              `limit_chars` when only a specific region matters.
            - The result includes `total_chars`, so you can decide whether the whole thing is
              worth reading at all.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("file", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "File name from the truncation notice, e.g. \"call_abc123.txt\". " +
                                "A bare id without the .txt suffix is also accepted."
                        )
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Character offset to start at. Default 0.")
                    })
                    put("limit_chars", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Characters to return, " +
                                "${ToolOutputRules.MIN_SLICE_CHARS}..${ToolOutputRules.MAX_SLICE_CHARS}. " +
                                "Default ${ToolOutputRules.MAX_SLICE_CHARS / 2}."
                        )
                    })
                },
                required = listOf("file"),
            )
        },
        execute = { arguments ->
            val params = runCatching { arguments.jsonObject }.getOrNull()
            val requested = params?.get("file")?.jsonPrimitive?.contentOrNull.orEmpty()
            val offset = params?.get("offset")?.jsonPrimitive?.intOrNull ?: 0
            val limit = params?.get("limit_chars")?.jsonPrimitive?.intOrNull
                ?: (ToolOutputRules.MAX_SLICE_CHARS / 2)

            val fileName = ToolOutputRules.resolveRequestedFile(requested)
            if (fileName == null) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "invalid_file")
                            put(
                                "detail",
                                "Expected a spill file name such as \"call_abc123.txt\". " +
                                    "Use the name printed in the truncation notice."
                            )
                        }.toString()
                    )
                )
            }

            val slice = read(fileName, offset, limit)
            if (slice == null) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "not_found")
                            put("file", fileName)
                            put(
                                "detail",
                                "No saved output under that name. It may have been cleared by a " +
                                    "backup restore or uninstall; re-running the tool is the only " +
                                    "way back."
                            )
                        }.toString()
                    )
                )
            }

            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("file", fileName)
                        put("offset", slice.offset)
                        put("next_offset", slice.nextOffset)
                        put("total_chars", slice.totalChars)
                        put("has_more", !slice.exhausted)
                        put("content", slice.content)
                    }.toString()
                )
            )
        },
    )
)

/**
 * Renders the notice that replaces an over-budget tool result.
 *
 * The notice is the only thing the model sees about what it is missing, so it states the
 * shape, the arithmetic, and the exact handle — a model that has to guess a file name will
 * either skip the data or invent one, and both cost more than the truncation saved.
 */
internal fun buildTruncationNotice(
    kind: ContentKind,
    originalTokens: Int,
    resultTokens: Int,
    totalChars: Int,
    fileName: String?,
    hasShellAccess: Boolean,
    body: String,
): String = buildString {
    appendLine("[Tool output truncated: $kind · $originalTokens → $resultTokens tokens · $totalChars characters total]")
    if (fileName != null) {
        appendLine("Full output saved as: $fileName")
        appendLine("Read it in pages with tool_output_read(file=\"$fileName\", offset=0, limit_chars=8000).")
        if (hasShellAccess) {
            appendLine("Or read it from the shell: `cat /tool_outputs/$fileName`.")
        }
    } else {
        appendLine("The full output could not be saved to disk; the text below is all that remains.")
    }
    appendLine()
    append(body)
}
