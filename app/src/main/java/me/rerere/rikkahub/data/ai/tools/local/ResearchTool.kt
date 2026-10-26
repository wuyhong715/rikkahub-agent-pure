package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.net.withEgressGuard
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.SearchResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import me.rerere.search.extract.ExtractMode
import me.rerere.search.extract.ExtractedPage
import me.rerere.search.extract.WebExtractor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

private const val RESEARCH_DEFAULT_SOURCES = 3
private const val RESEARCH_MAX_SOURCES = 5

/** One domain may contribute at most this many sources, so a single site cannot fill the pass. */
private const val RESEARCH_MAX_PER_DOMAIN = 2

private const val RESEARCH_DEFAULT_CHARS = 2_000
private const val RESEARCH_MIN_CHARS = 400
private const val RESEARCH_MAX_CHARS = 8_000

private const val RESEARCH_FETCH_TIMEOUT_MS = 15_000L
private const val RESEARCH_TOTAL_TIMEOUT_MS = 50_000L
private const val RESEARCH_MAX_BODY_BYTES = 2 * 1024 * 1024

/** What one URL yielded: a readable page, or the reason there is none. */
private data class PageFetch(
    val page: ExtractedPage?,
    val error: String?,
)

/** One source after the read, before it becomes JSON. Pure so the payload is unit-testable. */
internal data class SourceReading(
    val item: SearchResult.SearchResultItem,
    val title: String?,
    val author: String?,
    val publishedAt: String?,
    val text: String,
    val fetched: Boolean,
    val error: String? = null,
)

/** Host without `www.`, or "" when the URL cannot be parsed. */
internal fun domainOf(url: String): String =
    runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull().orEmpty()

/**
 * What a source looks like from the outside: `primary` when the domain is conventionally
 * first-hand (official docs, source repository, specification, government/education),
 * `reference` when the page declares an author or a date, `unknown` otherwise.
 *
 * This is a hint for the model, not a verdict: the rule set cannot know every project's
 * documentation site, so `unknown` means "judge this yourself", not "bad".
 */
internal fun sourceKind(url: String, author: String?, publishedAt: String?): String {
    val host = domainOf(url)
    if (host.isEmpty()) return "unknown"
    val primary = host.endsWith(".gov") ||
        host.endsWith(".edu") ||
        host.endsWith(".int") ||
        host.startsWith("docs.") ||
        host.startsWith("developer.") ||
        host == "github.com" ||
        host == "gitlab.com" ||
        host == "codeberg.org" ||
        host.startsWith("git.")
    if (primary) return "primary"
    return if (!author.isNullOrBlank() || !publishedAt.isNullOrBlank()) "reference" else "unknown"
}

/**
 * Keeps the provider's order but spreads the selection across domains: one site may supply at
 * most [RESEARCH_MAX_PER_DOMAIN]. Reordering by guessed quality would hide the provider's own
 * ranking from the model; only the [sourceKind] label is added later.
 */
internal fun pickSources(
    items: List<SearchResult.SearchResultItem>,
    max: Int,
): List<SearchResult.SearchResultItem> {
    if (max <= 0) return emptyList()
    val perDomain = mutableMapOf<String, Int>()
    val picked = ArrayList<SearchResult.SearchResultItem>(max)
    for (item in items) {
        val domain = domainOf(item.url)
        val used = perDomain[domain] ?: 0
        if (used >= RESEARCH_MAX_PER_DOMAIN) continue
        perDomain[domain] = used + 1
        picked.add(item)
        if (picked.size >= max) break
    }
    return picked
}

/**
 * The tool's whole result: sources in reading order, plus the checks that say what held.
 * [ResultCheck] entries are only emitted for promises this tool can actually break.
 */
internal fun buildResearchResult(
    query: String,
    provider: String,
    maxChars: Int,
    readings: List<SourceReading>,
): JsonObject {
    val total = readings.size
    val fetched = readings.count { it.fetched }
    val dated = readings.count { !it.publishedAt.isNullOrBlank() }
    val kinds = readings.map { sourceKind(it.item.url, it.author, it.publishedAt) }
    val primary = kinds.count { it == "primary" }
    val unknown = kinds.count { it == "unknown" }
    val reference = total - primary - unknown

    return buildJsonObject {
        put("query", query)
        put("provider", provider)
        put("retrieved_at", Clock.System.now().toString())
        put("sources", buildJsonArray {
            readings.forEach { reading ->
                add(buildJsonObject {
                    put("title", reading.title ?: reading.item.title)
                    put("url", reading.item.url)
                    put("domain", domainOf(reading.item.url))
                    put("kind", sourceKind(reading.item.url, reading.author, reading.publishedAt))
                    reading.publishedAt?.takeIf { it.isNotBlank() }?.let { put("published_at", it) }
                    reading.author?.takeIf { it.isNotBlank() }?.let { put("author", it) }
                    if (reading.publishedAt.isNullOrBlank()) put("date_missing", true)
                    put("excerpt", reading.text.take(maxChars).trim())
                    put("fetched", reading.fetched)
                    reading.error?.let { put("fetch_error", it) }
                })
            }
        })
        putChecks(
            listOf(
                ResultCheck("sources_found", total > 0, "$total sources selected"),
                ResultCheck(
                    "pages_fetched",
                    total > 0 && fetched == total,
                    "$fetched/$total pages read",
                ),
                ResultCheck(
                    "dates_present",
                    total > 0 && dated == total,
                    "$dated/$total sources declare a publication date",
                ),
                ResultCheck(
                    "has_primary_source",
                    primary > 0,
                    "primary=$primary reference=$reference unknown=$unknown",
                ),
            )
        )
        if (dated < total) {
            put(
                "note",
                "${total - dated} of $total sources declare no publication date; " +
                    "do not present those claims as current fact.",
            )
        }
    }
}

/**
 * T-11 — one research pass in one call.
 *
 * `search_web` + `scrape_web` still exist for targeted work. This tool exists for the common
 * case where the model would otherwise chain three calls and skip the comparison: it searches,
 * reads the top sources, and returns each one with its own date, author and source kind, so
 * freshness is data on the result instead of a rule in the prompt.
 */
fun researchTool(
    client: OkHttpClient,
    settingsProvider: () -> Settings,
): Tool = Tool(
    name = "research",
    description = """
        Run a web research pass in one call: search, read the top sources, and report each one
        with its domain, author and publication date.

        Use this instead of search_web + scrape_web when a claim needs checking (current facts,
        versions, releases, prices) rather than a list of links.

        Each source carries: title, url, domain; kind = primary (official docs, source
        repository, specification, .gov/.edu) | reference | unknown; published_at and author
        when the page declares them, and date_missing: true when it does not; excerpt (readable
        text, capped); fetched plus fetch_error when the page could not be read — the search
        snippet is kept in that case, so a partial answer survives.

        Read `checks` before answering. dates_present: false means some sources carry no date
        and must not be presented as current fact. When pages_fetched is false, say which
        sources were only snippets. Cite the urls you actually used.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "What to research; focused keywords beat a full question")
                })
                put("max_sources", buildJsonObject {
                    put("type", "integer")
                    put("description", "How many sources to read (1-5, default 3)")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Excerpt cap per source (400-8000, default 2000)")
                })
            },
            required = listOf("query"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val query = obj["query"]?.jsonPrimitive?.contentOrNull?.trim()
        if (query.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(fmErrEnvelope("missing_query", "query is required")))
        }
        val maxSources = (obj["max_sources"]?.jsonPrimitive?.intOrNull ?: RESEARCH_DEFAULT_SOURCES)
            .coerceIn(1, RESEARCH_MAX_SOURCES)
        val maxChars = (obj["max_chars"]?.jsonPrimitive?.intOrNull ?: RESEARCH_DEFAULT_CHARS)
            .coerceIn(RESEARCH_MIN_CHARS, RESEARCH_MAX_CHARS)

        val settings = settingsProvider()
        val options = settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT },
        )
        val service = SearchService.getService(options)
        // Providers disagree on the primary parameter name; take whatever the provider's own
        // schema marks required instead of assuming "query".
        val schema = service.parameters(options) as? InputSchema.Obj
        val primaryParam = schema?.let { s ->
            s.required?.firstOrNull()?.takeIf { name -> s.properties.containsKey(name) }
        } ?: "query"
        val searchParams = buildJsonObject { put(primaryParam, query) }

        val search = service.search(
            params = searchParams,
            commonOptions = settings.searchCommonOptions,
            serviceOptions = options,
        ).getOrElse { error ->
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "search_failed")
                        put("detail", error.message?.take(300) ?: "search provider returned an error")
                        put(
                            "recovery",
                            "Check the search provider and key in Settings › Search, " +
                                "or use search_web for a plain result list.",
                        )
                    }.toString()
                )
            )
        }

        val picked = pickSources(search.items, maxSources)
        if (picked.isEmpty()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "no_results")
                        put("query", query)
                        put("provider", service.name)
                        put(
                            "recovery",
                            "Rephrase the query, or answer from what you already have and say it is unverified.",
                        )
                    }.toString()
                )
            )
        }

        val readings = withTimeoutOrNull(RESEARCH_TOTAL_TIMEOUT_MS) {
            coroutineScope {
                picked.map { item ->
                    async {
                        val result = withTimeoutOrNull(RESEARCH_FETCH_TIMEOUT_MS) {
                            fetchPage(client, item.url, maxChars)
                        }
                        if (result == null) {
                            SourceReading(
                                item = item,
                                title = null,
                                author = null,
                                publishedAt = item.publishedDate,
                                text = item.text,
                                fetched = false,
                                error = "timeout",
                            )
                        } else {
                            SourceReading(
                                item = item,
                                title = result.page?.title,
                                author = result.page?.author,
                                publishedAt = result.page?.publishedAt ?: item.publishedDate,
                                text = result.page?.text?.takeIf { it.isNotBlank() } ?: item.text,
                                fetched = result.page != null,
                                error = result.error,
                            )
                        }
                    }
                }.awaitAll()
            }
        } ?: picked.map { item ->
            SourceReading(
                item = item,
                title = null,
                author = null,
                publishedAt = item.publishedDate,
                text = item.text,
                fetched = false,
                error = "timeout",
            )
        }

        val payload = buildResearchResult(
            query = query,
            provider = service.name,
            maxChars = maxChars,
            readings = readings,
        )
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

/**
 * Reads one page through the egress guard and extracts its readable text plus whatever the
 * page declares about itself. Failures come back as a reason, never as an exception: a
 * research pass with one dead source is worth more than no pass at all.
 */
private suspend fun fetchPage(
    client: OkHttpClient,
    url: String,
    maxChars: Int,
): PageFetch = withContext(Dispatchers.IO) {
    runCatching {
        val guarded = client.withEgressGuard()
            .newBuilder()
            .callTimeout(RESEARCH_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        guarded.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                return@use PageFetch(null, "http_${response.code}")
            }
            val contentType = response.header("Content-Type")
            if (contentType != null &&
                !contentType.contains("text/html", ignoreCase = true) &&
                !contentType.contains("text/plain", ignoreCase = true)
            ) {
                return@use PageFetch(null, "unsupported_content_type")
            }
            val (raw, _) = readBounded(response.body.byteStream(), RESEARCH_MAX_BODY_BYTES)
            val html = decodeBody(raw, raw.size, contentType)
            val page = WebExtractor.extract(
                html = html,
                baseUrl = response.request.url.toString(),
                mode = ExtractMode.ARTICLE,
                maxChars = maxChars,
                startIndex = 0,
            )
            val effective = if (page.text.isBlank()) {
                WebExtractor.extract(
                    html = html,
                    baseUrl = response.request.url.toString(),
                    mode = ExtractMode.TEXT,
                    maxChars = maxChars,
                    startIndex = 0,
                )
            } else {
                page
            }
            if (effective.text.isBlank()) {
                PageFetch(null, "empty_extraction")
            } else {
                PageFetch(effective, null)
            }
        }
    }.getOrElse { error ->
        PageFetch(null, (error.message ?: error::class.simpleName ?: "fetch_failed").take(120))
    }
}
