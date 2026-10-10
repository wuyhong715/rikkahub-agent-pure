package me.rerere.rikkahub.data.vector

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One passage `library_search` found, with the file it came from. */
data class LibrarySearchHit(
    /** Workspace-absolute path, so it can be handed straight to `workspace_read_file`. */
    val path: String,
    val chunkIndex: Int,
    val score: Float,
    val text: String,
)

/**
 * What `library_search` returns for one call.
 *
 * [available] is false when nothing can be embedded - no model installed, or one that does not fit
 * this device - and that is a state the tool reports rather than throws: a fresh install has no
 * embedding model, and the model still has to be told what to do instead.
 */
data class LibrarySearchOutcome(
    val available: Boolean,
    val hits: List<LibrarySearchHit> = emptyList(),
    val indexedFiles: Int = 0,
    /** Some passages were embedded by a different model: a rebuild is needed before trusting it. */
    val stale: Boolean = false,
    val note: String? = null,
)

/**
 * The JSON `library_search` answers with.
 *
 * Deliberately a sibling of [MemorySearchEnvelope] rather than a shared shape: the two differ in
 * the one place that matters to a model - what to do when the search comes back empty. Cold memory
 * has `memory_index` / `memory_read` to fall back on; the library has `find_files` for names and
 * `workspace_read_file` for contents, and a hint that names the wrong family is worse than no hint.
 */
object LibrarySearchEnvelope {

    fun hits(dirLabel: String, query: String, outcome: LibrarySearchOutcome): String =
        buildJsonObject {
            put("dir", dirLabel)
            put("query", query)
            put("indexedFiles", outcome.indexedFiles)
            if (outcome.stale) {
                put("stale", true)
                put(
                    "staleNote",
                    "Some passages were embedded by a different model; a rebuild restores full accuracy.",
                )
            }
            put("hits", buildJsonArray {
                outcome.hits.forEach { hit ->
                    add(buildJsonObject {
                        put("path", hit.path)
                        put("chunkIndex", hit.chunkIndex)
                        put("score", hit.score)
                        put("text", hit.text.take(MAX_HIT_CHARS))
                        if (hit.text.length > MAX_HIT_CHARS) put("truncated", true)
                    })
                }
            })
            if (outcome.hits.isEmpty()) {
                put("note", emptyNote(outcome.indexedFiles))
                if (outcome.note != null) put("detail", outcome.note!!)
            }
        }.toString()

    /**
     * Sent when no embedding model is installed. The hint matters more than the error: a model that
     * only finds out semantic search is off will otherwise stop looking through the files entirely,
     * when `find_files` (by name) and `workspace_read_file` (by content it can see) still work.
     */
    fun unavailable(detail: String?): String = buildJsonObject {
        put("error", "unavailable")
        put("detail", detail ?: "no embedding model is installed")
        put(
            "hint",
            "Use find_files to look for a name, or workspace_read_file to open a file you already know.",
        )
    }.toString()

    fun missingQuery(): String = buildJsonObject {
        put("error", "missing_argument")
        put("detail", "query is required")
    }.toString()

    fun notConfigured(): String = buildJsonObject {
        put("error", "not_configured")
        put("detail", "this assistant has no file library configured")
    }.toString()

    private fun emptyNote(indexedFiles: Int): String = if (indexedFiles == 0) {
        "Nothing is indexed for this directory yet. Use find_files for a name, or ask the user to " +
            "rebuild the file library index."
    } else {
        "No passage matched. Try different words, or find_files if you know part of the name."
    }
}
