package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试: 工具输出的 JSON 形状不可信 —— 模型或上游可能给出任意字段类型, 输出整体也可能
 * 是纯文本。旧版 ShellToolUI.Summary 直接对 tool 输出调用 `JsonElement.getJsonArray`, 当内容是
 * JsonLiteral 时抛 `IllegalArgumentException: Element class kotlinx.serialization.json.JsonLiteral
 * is not a JsonArray`, 整个聊天界面崩溃 (崩溃栈: ShellToolUI.Summary → JsonElementKt.getJsonArray)。
 * (注: 当前 kotlinx-serialization-json 1.11.0 的访问器是 `jsonArray` 等属性形式, 旧客户端用的是
 * 更早版本的 `getJsonArray()` 函数形式; 两者对类型不符都抛 IllegalArgumentException。)
 *
 * 所有渲染器对输出字段的访问必须走 null 安全的 jsonObjectOrNull / jsonArrayOrNull /
 * jsonPrimitiveOrNull, 类型不符时安全回退而不是抛异常。
 */
class ToolUIsJsonSafetyTest {

    private fun parse(s: String) = Json.parseToJsonElement(s)

    // ---- shellSummaryOf (ShellToolUI.Summary / Preview) ----

    @Test
    fun `shell summary parses normal object output`() {
        val summary = shellSummaryOf(parse("""{"exitCode":0,"stdout":"hello\nworld","stderr":"","timedOut":false}"""))

        assertEquals(0, summary.exitCode)
        assertFalse(summary.timedOut)
        assertEquals("hello\nworld", summary.stdout)
        assertEquals("", summary.stderr)
        assertEquals("hello\nworld", summary.output)
    }

    @Test
    fun `shell summary keeps stdout and stderr separate for the preview`() {
        val summary = shellSummaryOf(parse("""{"stdout":"out","stderr":"err","exitCode":2}"""))

        assertEquals("out", summary.stdout)
        assertEquals("err", summary.stderr)
        assertEquals("out\nerr", summary.output)
    }

    @Test
    fun `shell summary does not throw when the whole output is a plain JSON literal`() {
        // 旧崩溃的输入形状: tool 输出是纯文本, parseToolOutputContent 把它解析成 JsonLiteral,
        // 旧代码对 content 调 getJsonArray 直接抛 IllegalArgumentException。
        val summary = shellSummaryOf(JsonPrimitive("ls -la"))

        assertNull(summary.exitCode)
        assertFalse(summary.timedOut)
        assertEquals("", summary.output)
    }

    @Test
    fun `shell summary tolerates stdout given as a JSON array`() {
        // 旧代码假设 stdout/stderr 是行数组; 运行时实际给的是字符串。数组必须被安全忽略。
        val content = buildJsonObject {
            put("exitCode", JsonPrimitive(1))
            put("stdout", buildJsonArray { add(JsonPrimitive("line1")); add(JsonPrimitive("line2")) })
        }

        val summary = shellSummaryOf(content)

        assertEquals(1, summary.exitCode)
        assertEquals("", summary.stdout)
        assertEquals("", summary.output)
    }

    @Test
    fun `shell summary tolerates non-object content`() {
        val summary = shellSummaryOf(buildJsonArray { add(JsonPrimitive("x")) })

        assertNull(summary.exitCode)
        assertEquals("", summary.output)
    }

    @Test
    fun `shell summary tolerates null content`() {
        val summary = shellSummaryOf(null)

        assertNull(summary.exitCode)
        assertFalse(summary.timedOut)
        assertEquals("", summary.output)
    }

    // ---- getStringContent (所有渲染器共用的字段读取) ----

    @Test
    fun `getStringContent returns null instead of throwing on a JSON literal`() {
        // 旧崩溃的直接诱因: 对 JsonLiteral 调 getJsonArray。安全版必须返回 null。
        assertNull(JsonPrimitive("plain").getStringContent("stdout"))
        assertNull(JsonPrimitive("plain").getStringContent("exitCode"))
        assertNull(JsonPrimitive("plain").getStringContent("nonexistent"))
    }

    @Test
    fun `getStringContent returns null for non-primitive fields`() {
        val obj = buildJsonObject { put("stdout", buildJsonArray { add(JsonPrimitive("x")) }) }

        assertNull(obj.getStringContent("stdout"))
    }

    // ---- searchItemsOf (SearchWebToolUI.Summary / Preview) ----

    @Test
    fun `search items parses object output`() {
        val items = searchItemsOf(parse("""{"answer":"ok","items":[{"url":"https://a.example"}]}"""))

        assertEquals(1, items.size)
        assertEquals("https://a.example", items[0].getStringContent("url"))
    }

    @Test
    fun `search items falls back to empty for non-object or literal items field`() {
        assertTrue(searchItemsOf(JsonPrimitive("plain text")).isEmpty())
        assertTrue(searchItemsOf(buildJsonArray { add(JsonPrimitive("x")) }).isEmpty())

        val obj = buildJsonObject { put("items", JsonPrimitive("not an array")) }
        assertTrue(searchItemsOf(obj).isEmpty())
    }

    // ---- scrapeUrlsOf / scrapeUrlEntryOf (ScrapeWebToolUI.Preview) ----

    @Test
    fun `scrape urls parses object output`() {
        val urls = scrapeUrlsOf(parse("""{"urls":[{"url":"https://a.example","content":"markdown"}]}"""))

        assertEquals(1, urls.size)
        assertEquals("https://a.example", scrapeUrlEntryOf(urls[0])?.url)
        assertEquals("markdown", scrapeUrlEntryOf(urls[0])?.content)
    }

    @Test
    fun `scrape urls falls back to empty for non-object content or literal urls field`() {
        assertTrue(scrapeUrlsOf(JsonPrimitive("plain text")).isEmpty())
        assertTrue(scrapeUrlsOf(buildJsonArray { add(JsonPrimitive("x")) }).isEmpty())

        val obj = buildJsonObject { put("urls", JsonPrimitive("https://a.example")) }
        assertTrue(scrapeUrlsOf(obj).isEmpty())
    }

    @Test
    fun `scrape url entry skips entries that are not JSON objects`() {
        // scrape_web 输出 urls 为字符串数组时 (完全可能), 条目必须是 JsonObject 才能渲染。
        assertNull(scrapeUrlEntryOf(JsonPrimitive("https://a.example")))
    }

    // ---- 崩溃根因文档化: 对 JsonLiteral 访问数组属性确实抛异常 ----

    @Test(expected = IllegalArgumentException::class)
    fun `jsonArray on a JSON literal throws exactly the reported crash`() {
        // 复现用户崩溃栈的核心调用。旧客户端 (kotlinx-serialization < 1.7) 的访问器是
        // `getJsonArray()` 函数, 报错 "Element class ... is not a JsonArray"; 当前
        // 1.11.0 里等价的是 `jsonArray` 属性, 抛同样的 IllegalArgumentException。
        JsonPrimitive("plain").jsonArray
    }
}
