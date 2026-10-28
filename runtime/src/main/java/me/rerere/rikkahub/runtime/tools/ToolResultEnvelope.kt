package me.rerere.rikkahub.runtime.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Keeps a truncated tool result **structured** instead of replacing it with prose.
 *
 * The problem this solves is not cosmetic. Moxw's tool results are JSON objects that two consumers
 * read from the same string:
 *
 * - the UI parses it into `ToolUIContext.content` and each registered `ToolUIRenderer` pulls fields
 *   out of it (`text`, `path`, `totalLines`, …);
 * - the model reads it as the tool's answer.
 *
 * `maybeTruncateToolOutput` used to replace that object with a plain-text notice ("[Tool output
 * truncated: …] Full output saved as: …") followed by the body. The model coped — the notice tells
 * it where the file is — but every structured field vanished with it, so a large `Read` lost its
 * code card, a large `Grep` lost its match list, and the file path the card is titled with was
 * gone. The truncation budget is a token decision; destroying the schema to enforce it is not.
 *
 * So: keep the original JSON, put the (already head+tail compressed) body back into the field it
 * came from, cap every other oversized field with an elision marker, and add the truncation
 * arithmetic as first-class keys. A renderer that reads `text` keeps working; a renderer that wants
 * to show a "truncated" chip reads [TRUNCATED_KEY]; a replay/debug consumer reads the token counts.
 *
 * Returns null when the original text is not a JSON object, or is an object with no string field to
 * carry the body — in that case the caller falls back to the plain-text notice, which is the only
 * shape that can represent it.
 */
internal object ToolResultEnvelope {

    /** Result key set to `true` when the body was shortened. Read by the UI, ignored by tools. */
    const val TRUNCATED_KEY = "truncated"

    /** Where the untouched full text was spilled, when the host could write it. */
    const val SPILL_FILE_KEY = "spillFile"

    /** One-line statement of what happened and where the full text is. */
    const val NOTICE_KEY = "notice"

    /** Other string fields longer than this are elided, so one payload cannot smuggle its budget. */
    private const val OTHER_STRING_MAX_CHARS = 2_000

    /** Nested arrays/objects larger than this (serialized) are replaced by a size marker. */
    private const val OTHER_BLOB_MAX_CHARS = 2_000

    /** Keys this envelope owns; never eligible to carry the body on a second pass. */
    private val RESERVED_KEYS = setOf(TRUNCATED_KEY, SPILL_FILE_KEY, NOTICE_KEY)

    /**
     * Payload fields Moxw's tools actually use, best first. Explicit beats "longest string": after
     * one pass the body is short enough that a `path` can be longer than it, and a length heuristic
     * would then stuff the body into the path field. Ordering also matches the tool contract —
     * `text` for file reads, `content` for slices, `output`/`stdout` for shell.
     */
    private val BODY_KEY_PRIORITY = listOf("text", "content", "output", "stdout", "stderr", "result", "body")

    /** Everything the caller knows about the shortening, rendered by [buildTruncationHeader]. */
    data class Meta(
        val notice: String,
        val originalTokens: Int,
        val resultTokens: Int,
        val totalChars: Int,
        val spillFile: String?,
    )

    /**
     * Rebuilds [originalText] as a JSON object carrying [body].
     *
     * @param originalText the tool's full result, before truncation.
     * @param body the head+tail compressed replacement for its largest string field.
     * @param meta truncation arithmetic and the notice line.
     * @return the rebuilt JSON, or null when the original cannot carry a body.
     */
    fun rebuild(originalText: String, body: String, meta: Meta): String? {
        val root = runCatching { Json.parseToJsonElement(originalText) }.getOrNull()
        if (root !is JsonObject) return null

        // The body goes back into the field it came from. Named fields first (see
        // BODY_KEY_PRIORITY), longest remaining string second, meta keys never.
        val bodyKey = BODY_KEY_PRIORITY.firstOrNull { key ->
            val value = root[key]
            key !in RESERVED_KEYS && value is JsonPrimitive && value.isString
        } ?: root.entries
            .filter { (key, value) -> key !in RESERVED_KEYS && value is JsonPrimitive && value.isString }
            .maxByOrNull { (_, value) -> (value as JsonPrimitive).content.length }
            ?.key
            ?: return null

        return buildJsonObject {
            for ((key, value) in root) {
                when {
                    key == bodyKey -> put(key, body)
                    key == TRUNCATED_KEY || key == SPILL_FILE_KEY || key == NOTICE_KEY -> Unit // rewritten below
                    value.isOversized() -> put(key, elisionMarker(value))
                    else -> put(key, value)
                }
            }
            put(TRUNCATED_KEY, true)
            put("originalTokens", meta.originalTokens)
            put("resultTokens", meta.resultTokens)
            put("totalChars", meta.totalChars)
            meta.spillFile?.let { put(SPILL_FILE_KEY, it) }
            put(NOTICE_KEY, meta.notice)
        }.toString()
    }

    private fun JsonElement.isOversized(): Boolean = when (this) {
        is JsonPrimitive -> isString && content.length > OTHER_STRING_MAX_CHARS
        is JsonArray, is JsonObject -> toString().length > OTHER_BLOB_MAX_CHARS
    }

    /** A compact placeholder that says how much was dropped, without carrying any of it. */
    private fun elisionMarker(value: JsonElement): JsonPrimitive =
        JsonPrimitive("…[${value.toString().length} characters elided to fit the tool-result budget]")
}
