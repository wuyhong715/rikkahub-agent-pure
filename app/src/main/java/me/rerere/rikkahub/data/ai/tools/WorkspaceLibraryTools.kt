package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.vector.DEFAULT_SEARCH_LIMIT
import me.rerere.rikkahub.data.vector.LibrarySearchEnvelope
import me.rerere.rikkahub.data.vector.LibrarySearchOutcome
import me.rerere.rikkahub.data.vector.MAX_SEARCH_LIMIT

/**
 * Moxw - semantic search over the workspace file library.
 *
 * Registering this does NOT depend on an embedding model being installed: the tool reports that
 * state itself, which is how the model finds out to fall back to `find_files` instead of
 * concluding the files are not there.
 *
 * The sibling tools are exact and free - `find_files` matches names, `workspace_read_file` opens a
 * file you already know - and a model that can name what it wants should not pay for an embedding
 * call to be told it. What this adds is finding a file by what is *in* it, across a directory the
 * model has never listed.
 */
fun buildLibrarySearchTool(
    dirLabel: String,
    search: suspend (query: String, limit: Int) -> LibrarySearchOutcome,
): Tool = Tool(
    name = "library_search",
    description = """
        Search the workspace file library by meaning, and get back the passages that match, with
        the file each one came from.

        Use this when you are looking for something you cannot name - "where did we write about the
        retry policy", "which document describes the deployment", "anything about the streaming
        bug" - across files the user has put in their library. It reads code, prose and documents
        (PDF, Word, PowerPoint, EPUB) alike.

        Prefer `find_files` when you know part of a file name, and `workspace_read_file` when you
        already know which file to open: both are exact and cost nothing.

        Each hit names a path you can pass straight to `workspace_read_file` when a passage is not
        enough.

        Scores are cosine similarities between the query and each passage, and they are NOT
        comparable between calls: a loosely related passage of one query can outscore a perfect
        match of another. Compare the passages within one result set; never treat a particular
        number as a relevance threshold.

        If it reports that semantic search is unavailable, no embedding model is installed. Say so
        to the user rather than retrying - there is no keyword fallback any more.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "What to look for, in natural language or keywords.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum passages to return. Defaults to ${DEFAULT_SEARCH_LIMIT}.")
                })
            },
            required = listOf("query"),
        )
    },
    needsApproval = { false },
    execute = {
        val params = it.jsonObject
        val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text(LibrarySearchEnvelope.missingQuery()))
        }
        // Clamped rather than refused, for the same reason `memory_search` clamps: a model asking
        // for a hundred passages is over-eager, not wrong.
        val limit = params["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, MAX_SEARCH_LIMIT)
            ?: DEFAULT_SEARCH_LIMIT

        val outcome = search(query, limit)
        val body = if (outcome.available) {
            LibrarySearchEnvelope.hits(dirLabel, query, outcome)
        } else {
            LibrarySearchEnvelope.unavailable(outcome.note)
        }
        listOf(UIMessagePart.Text(body))
    },
)
