package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.search.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchToolTest {

    private fun item(
        url: String,
        title: String = "title",
        text: String = "body",
        date: String? = null,
    ) = SearchResult.SearchResultItem(
        title = title,
        url = url,
        text = text,
        publishedDate = date,
    )

    private fun JsonObject.check(name: String): Pair<Boolean, String> {
        val entry = this["checks"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == name }
        return entry["ok"]!!.jsonPrimitive.content.toBoolean() to
            entry["evidence"]!!.jsonPrimitive.content
    }

    @Test
    fun `domain drops www and survives an unparseable url`() {
        assertEquals("example.com", domainOf("https://www.example.com/a"))
        assertEquals("developer.android.com", domainOf("https://developer.android.com/guide"))
        assertEquals("", domainOf("not a url"))
        assertEquals("", domainOf(""))
    }

    @Test
    fun `source kind treats official docs and repositories as primary`() {
        assertEquals("primary", sourceKind("https://developer.android.com/guide", null, null))
        assertEquals("primary", sourceKind("https://docs.python.org/3/", null, null))
        assertEquals("primary", sourceKind("https://github.com/foo/bar", null, null))
        assertEquals("primary", sourceKind("https://example.gov/notice", null, null))
        // A page that says who wrote it and when is at least attributable.
        assertEquals("reference", sourceKind("https://example.com/post", "Ada", null))
        assertEquals("reference", sourceKind("https://example.com/post", null, "2026-01-01"))
        assertEquals("unknown", sourceKind("https://example.com/post", null, null))
    }

    @Test
    fun `picking sources spreads across domains and keeps provider order`() {
        val items = listOf(
            item("https://a.com/1"),
            item("https://a.com/2"),
            item("https://a.com/3"),
            item("https://b.com/1"),
            item("https://c.com/1"),
        )
        val picked = pickSources(items, max = 3)
        assertEquals(
            listOf("https://a.com/1", "https://a.com/2", "https://b.com/1"),
            picked.map { it.url },
        )
    }

    @Test
    fun `picking nothing is allowed and returns nothing`() {
        assertTrue(pickSources(listOf(item("https://a.com/1")), max = 0).isEmpty())
    }

    @Test
    fun `checks report a pass that ran but found no dates`() {
        val result = buildResearchResult(
            query = "anything",
            provider = "Test",
            maxChars = 100,
            readings = listOf(
                SourceReading(
                    item = item("https://example.com/a"),
                    title = null,
                    author = null,
                    publishedAt = null,
                    text = "body",
                    fetched = true,
                ),
                SourceReading(
                    item = item("https://example.org/b"),
                    title = null,
                    author = "Ada",
                    publishedAt = "2026-01-01",
                    text = "body",
                    fetched = false,
                    error = "http_403",
                ),
            ),
        )

        assertTrue(result.check("sources_found").first)
        assertFalse(result.check("pages_fetched").first)
        assertEquals("1/2 pages read", result.check("pages_fetched").second)
        assertFalse(result.check("dates_present").first)
        assertEquals("1/2 sources declare a publication date", result.check("dates_present").second)
        assertTrue(result.containsKey("note"))

        val sources = result["sources"]!!.jsonArray.map { it.jsonObject }
        assertTrue(sources[0]["date_missing"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(sources[1].containsKey("date_missing"))
        assertEquals("http_403", sources[1]["fetch_error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a fully read dated pass has no note and every check holds`() {
        val result = buildResearchResult(
            query = "anything",
            provider = "Test",
            maxChars = 100,
            readings = listOf(
                SourceReading(
                    item = item("https://developer.android.com/x"),
                    title = "Docs",
                    author = null,
                    publishedAt = "2026-10-01",
                    text = "body",
                    fetched = true,
                ),
            ),
        )

        assertFalse(result.containsKey("note"))
        listOf("sources_found", "pages_fetched", "dates_present", "has_primary_source")
            .forEach { name -> assertTrue("check failed: $name", result.check(name).first) }
        assertEquals(
            "primary",
            result["sources"]!!.jsonArray[0].jsonObject["kind"]!!.jsonPrimitive.content,
        )
    }
}
