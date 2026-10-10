package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

// internal (not private) because ChatService imports TOOL_CATALOG_MAX_SUMMARY_CHARS to build
// catalog summaries of the same length this file enforces when it serialises search hits.
internal const val TOOL_CATALOG_MAX_SEARCH_RESULTS = 8
internal const val TOOL_CATALOG_MAX_OPEN_PER_CALL = 4
internal const val TOOL_CATALOG_MAX_ACTIVE_SCHEMAS = 6
internal const val TOOL_CATALOG_MAX_SUMMARY_CHARS = 180

/** Where a catalogued entry comes from; used for scoring and serialized in search hits. */
enum class ToolCatalogSource { LOCAL, MCP, SKILL }

/**
 * What the vector channel could answer for one `tool_search` query.
 *
 * Moxw searches the catalogue by meaning or not at all — the lexical path this file once fell back
 * to is gone, so "nothing can be ranked right now" is a first-class answer rather than a silent
 * downgrade to keyword matching. It is reported to the model verbatim, including what to do about
 * it, because that is the only way the user ever learns the embedding model is missing.
 */
sealed interface ToolSearchAnswer {
    /** Ranked entries, best first. Empty means the catalogue holds nothing close to this query. */
    data class Ranked(val entries: List<ToolCatalogEntry>) : ToolSearchAnswer

    /**
     * The catalogue cannot be ranked right now — no embedding model installed, or its vectors are
     * still being produced. [note] is written for the model to relay to the user.
     */
    data class Unavailable(val note: String) : ToolSearchAnswer
}

/**
 * One catalog entry.
 *
 * A [ToolCatalogSource.SKILL] entry stands for a *skill*, not a tool schema: nothing is injected
 * when such a name is activated, the skill simply becomes visible to `use_skill` (which is what the
 * model needs in order to load it). Its [tool] is therefore never attached — see
 * [ToolRankFusion.attachOfSource]'s use at the call site.
 *
 * [summary] is authored by the caller. This file never truncates the stored value;
 * it only enforces the 180-char cap at serialization time. [tool] is carried so the
 * caller can materialize a real schema once the name is activated — it is never
 * serialized here (it contains lambdas).
 */
data class ToolCatalogEntry(
    val name: String,
    val summary: String,
    val source: ToolCatalogSource,
    val tool: Tool,
)

/**
 * Immutable, order-preserving catalog.
 *
 * Why a class rather than a bare list: O(1) name lookup plus the scoring logic lives
 * next to the data it ranks. Fully immutable, so no locking is needed.
 */
class ToolCatalog(entries: List<ToolCatalogEntry>) {
    val entries: List<ToolCatalogEntry> = entries.toList()
    private val byName: Map<String, ToolCatalogEntry> = entries.associateBy { it.name }
    val size: Int get() = entries.size

    fun entry(name: String): ToolCatalogEntry? = byName[name]

    fun search(query: String, limit: Int = TOOL_CATALOG_MAX_SEARCH_RESULTS): List<ToolCatalogEntry> =
        matchAll(query).take(limit.coerceIn(1, TOOL_CATALOG_MAX_SEARCH_RESULTS))
}

/**
 * Result of one `tool_open` call, bucketed so the model can tell exactly why each
 * requested name did or did not become active.
 */
data class ToolOpenOutcome(
    val activated: List<String>,
    val alreadyActive: List<String>,
    val unknown: List<String>,
    val rejectedOverCap: List<String>,
    val activeCount: Int,
) {
    fun toJson(): String {
        val note = buildString {
            append("Activated schemas become callable from your NEXT turn, not this one.")
            if (activated.isEmpty()) {
                if (unknown.isEmpty() && alreadyActive.isEmpty() && rejectedOverCap.isEmpty()) {
                    append(" No tool names were provided, so nothing was activated. Call tool_search")
                    append(" first to get exact names, then pass them in the names array.")
                } else {
                    append(" Nothing was activated. Retry: run tool_search first to get exact names,")
                    append(" then call tool_open with those names.")
                }
            }
        }
        return Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("activated", JsonArray(activated.map { JsonPrimitive(it) }))
                put("alreadyActive", JsonArray(alreadyActive.map { JsonPrimitive(it) }))
                put("unknown", JsonArray(unknown.map { JsonPrimitive(it) }))
                put("rejectedOverCap", JsonArray(rejectedOverCap.map { JsonPrimitive(it) }))
                put("activeCount", activeCount)
                put("note", note)
            },
        )
    }
}

/** A ranked search hit — name + summary + source only, deliberately schema-free. */
data class ToolSearchHit(val name: String, val summary: String, val source: ToolCatalogSource)

data class ToolSearchOutcome(
    val hits: List<ToolSearchHit>,
    val total: Int,
    val truncated: Boolean,
) {
    fun toJson(): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("total", total)
            put("returned", hits.size)
            put("truncated", truncated)
            put("results", JsonArray(hits.map { hit ->
                buildJsonObject {
                    put("name", hit.name)
                    put("summary", hit.summary.take(TOOL_CATALOG_MAX_SUMMARY_CHARS))
                    put("source", hit.source.name)
                }
            }))
            if (hits.isEmpty()) {
                put("note", "No tools matched. Try describing the task differently, or in other words.")
            }
        },
    )

    companion object {
        /**
         * The answer when the catalogue cannot be searched at all. Shaped like every other search
         * envelope in this app — `available: false` plus a note — so the model reads one convention
         * wherever it looks.
         */
        fun unavailable(note: String): String = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("available", false)
                put("note", note)
            },
        )
    }
}

/**
 * Session-scoped, thread-safe set of activated tool names. Never persisted — it lives
 * only for the lifetime of the conversation, which is exactly the "progressive"
 * contract (activation is per-session state, not data).
 */
class ToolActivationState {
    private val lock = Any()
    private val activeNames = LinkedHashSet<String>()

    /** Snapshot in insertion order (fresh copy, safe for the caller to mutate). */
    fun active(): Set<String> = synchronized(lock) { activeNames.toSet() }

    fun clear() = synchronized(lock) { activeNames.clear() }

    /** Drop active names that fell out of the catalog (e.g. an MCP server disconnected). */
    fun retain(knownNames: Set<String>) = synchronized(lock) { activeNames.retainAll(knownNames) }

    /**
     * Activate [names] in the order given.
     *
     * Duplicate handling: the first occurrence is the one that activates; any later
     * occurrence is reported in [ToolOpenOutcome.alreadyActive], because by then the
     * name is already (or just became) active. Unknown names never consume the per-call
     * budget, and the per-call budget is applied first-come-first-served.
     */
    fun activate(catalog: ToolCatalog, names: List<String>): ToolOpenOutcome = synchronized(lock) {
        val activated = mutableListOf<String>()
        val alreadyActive = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        val rejectedOverCap = mutableListOf<String>()
        var openedThisCall = 0
        for (name in names) {
            if (catalog.entry(name) == null) {
                unknown.add(name)
                continue
            }
            if (name in activeNames) {
                alreadyActive.add(name)
                continue
            }
            if (openedThisCall >= TOOL_CATALOG_MAX_OPEN_PER_CALL) {
                rejectedOverCap.add(name)
                continue
            }
            if (activeNames.size >= TOOL_CATALOG_MAX_ACTIVE_SCHEMAS) {
                rejectedOverCap.add(name)
                continue
            }
            activeNames.add(name)
            activated.add(name)
            openedThisCall++
        }
        ToolOpenOutcome(
            activated = activated,
            alreadyActive = alreadyActive,
            unknown = unknown,
            rejectedOverCap = rejectedOverCap,
            activeCount = activeNames.size,
        )
    }
}

/**
 * The two catalogue tools that replace a full schema dump.
 *
 * Returns exactly two tools: `tool_search` (find names, no schema) and `tool_open`
 * (activate names, no schema). Real schemas surface only on the model's next turn,
 * which is what keeps the request small.
 */
fun buildToolCatalogTools(
    catalog: ToolCatalog,
    activation: ToolActivationState,
    /**
     * How `tool_search` ranks. A parameter rather than something this file reaches for, because
     * [ToolCatalog] stays pure and model-free and the chat path owns the decision to embed.
     *
     * Moxw — **required, and vector-only.** This product is built around a local embedding model
     * being installed, so there is no lexical fallback left to take: an unrankable query yields
     * [ToolSearchAnswer.Unavailable], never a keyword pass. That is exactly the case that used to
     * fail silently — a request written in Chinese shares no characters with `scrape_web`, and a
     * keyword ranking returns nothing at all for it.
     */
    semanticSearch: suspend (String) -> ToolSearchAnswer,
): List<Tool> = listOf(
    Tool(
        name = "tool_search",
        description = """
            Search the tool catalog by meaning and return matching tool names with short
            summaries. Use this whenever you are unsure which tools exist. Describe what you want
            to do in your own words and in any language: the catalog is matched semantically, so a
            whole sentence works as well as a tool's exact name. Skills are searched too and
            come back with the source SKILL. It returns names and
            summaries ONLY — never schemas or parameters. To actually call a tool, first
            activate it with tool_open; activation takes effect on your NEXT turn, not the
            current one.
            If it reports that semantic search is unavailable, the embedding model is missing or
            still warming up. Say so to the user and stop rather than retrying with other words:
            searching again cannot fix it.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to match against tool names, summaries, and sources.")
                    })
                },
                required = listOf("query"),
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            // Moxw — one channel, and it is the vector one. Every entry comes from this catalogue,
            // so a returned name can be handed straight back to `tool_open` whichever branch ran.
            // An `Unavailable` answer is passed to the model verbatim, install instructions and
            // all: the exact-but-blind keyword ranking it replaces is what this product removed.
            val body = when (val answer = semanticSearch(query)) {
                is ToolSearchAnswer.Unavailable -> ToolSearchOutcome.unavailable(answer.note)
                is ToolSearchAnswer.Ranked -> {
                    val limited = answer.entries.take(TOOL_CATALOG_MAX_SEARCH_RESULTS)
                    ToolSearchOutcome(
                        hits = limited.map { e -> ToolSearchHit(e.name, e.summary, e.source) },
                        total = answer.entries.size,
                        truncated = answer.entries.size > limited.size,
                    ).toJson()
                }
            }
            listOf(UIMessagePart.Text(body))
        },
    ),
    Tool(
        name = "tool_open",
        description = """
            Activate entries by name. A tool's schema becomes callable; a skill (source
            SKILL) becomes available to `use_skill` instead, since a skill is instructions
            rather than a schema. Names must be exact matches returned by tool_search.
            Activation takes effect on your NEXT turn, not the current one. Do not
            re-open an entry that is already active.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("names", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Exact tool names to activate, as returned by tool_search.")
                    })
                },
                required = listOf("names"),
            )
        },
        execute = {
            val names = parseNames(it)
            listOf(UIMessagePart.Text(activation.activate(catalog, names).toJson()))
        },
    ),
)

/**
 * Full, untruncated, ranked match list — the single home of the scoring logic.
 *
 * [ToolCatalog.search] truncates it, and `tool_search` reuses it to compute the untruncated
 * `total` without duplicating the scoring. P2-05 widened it from `private` to `internal` for one
 * more caller: [LocalToolPalette], the expert editor's palette. The 8-hit cap is a *response*
 * budget for a model, not a bound a human scrolling a list should inherit, so the palette ranks
 * with this and applies its own (larger) bound instead. Nothing about the function changed — the
 * body, the ordering and the tie-break are exactly what they were.
 */
internal fun ToolCatalog.matchAll(query: String): List<ToolCatalogEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val tokens = q.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
    return entries
        .mapNotNull { entry ->
            val score = matchScore(q, tokens, entry)
            if (score > 0) entry to score else null
        }
        .sortedWith(compareByDescending<Pair<ToolCatalogEntry, Int>> { it.second }.thenBy { it.first.name })
        .map { it.first }
}

private fun matchScore(query: String, tokens: List<String>, entry: ToolCatalogEntry): Int {
    val name = entry.name.lowercase()
    val summary = entry.summary.lowercase()
    val sourceName = entry.source.name.lowercase()
    var score = 0
    if (query.isNotEmpty()) {
        if (name == query) {
            score += 100
        } else if (name.contains(query)) {
            score += 40
        }
    }
    for (token in tokens) {
        if (name.contains(token)) score += 10
        if (summary.contains(token)) score += 4
        if (sourceName.contains(token)) score += 2
    }
    return score
}

private fun parseNames(element: JsonElement): List<String> {
    val namesElement = element.jsonObject["names"] ?: return emptyList()
    if (namesElement !is JsonArray) return emptyList()
    return namesElement
        .mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull }
        .filter { it.isNotBlank() }
}
