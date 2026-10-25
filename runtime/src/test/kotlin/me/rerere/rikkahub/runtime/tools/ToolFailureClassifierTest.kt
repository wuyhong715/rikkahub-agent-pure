package me.rerere.rikkahub.runtime.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 I — 失败归因的判定规则。这里错了会让下一轮拿着错的结论去「修」一个不存在的问题，
 * 所以每个分支都钉死，尤其是几个容易互相吞掉的顺序：缺依赖 vs 路径不存在、连接失败 vs 404。
 */
class ToolFailureClassifierTest {

    @Test
    fun `plain success is not a failure`() {
        assertNull(ToolFailureClassifier.detect("""{"path":"/workspace/a.md","sizeBytes":120}"""))
        assertNull(ToolFailureClassifier.detect("ok, done"))
        assertNull(ToolFailureClassifier.detect(""))
    }

    @Test
    fun `error envelope is detected with its code`() {
        val failure = ToolFailureClassifier.detect("""{"error":"permission_denied","detail":"EACCES: /sdcard/x"}""")
        assertNotNull(failure)
        assertEquals(ToolFailureKind.PERMISSION, failure!!.kind)
        assertTrue(failure.detail.contains("EACCES"))
    }

    @Test
    fun `success false is a failure even without an error code`() {
        val failure = ToolFailureClassifier.detect("""{"success":false,"detail":"connection refused"}""")
        assertEquals(ToolFailureKind.NETWORK, failure?.kind)
    }

    @Test
    fun `text errors are recognised`() {
        assertEquals(
            ToolFailureKind.TIMEOUT,
            ToolFailureClassifier.detect("Error: request timed out after 30s")?.kind,
        )
        assertNull("正常文本不该被当成错误", ToolFailureClassifier.detect("Errors: 0 warnings: 2"))
    }

    @Test
    fun `missing dependency wins over generic not found`() {
        assertEquals(
            ToolFailureKind.ENVIRONMENT,
            ToolFailureClassifier.classifyText("bash: pytest: command not found"),
        )
    }

    @Test
    fun `connection failure wins over not found`() {
        assertEquals(
            ToolFailureKind.NETWORK,
            ToolFailureClassifier.classifyText("Could not resolve host: api.example.com"),
        )
        assertEquals(
            ToolFailureKind.NETWORK,
            ToolFailureClassifier.classifyText("SSL handshake failed"),
        )
    }

    @Test
    fun `file not found stays not found`() {
        assertEquals(
            ToolFailureKind.NOT_FOUND,
            ToolFailureClassifier.classifyText("no such file or directory: /workspace/report.md"),
        )
    }

    @Test
    fun `auth before permission`() {
        assertEquals(
            ToolFailureKind.AUTH,
            ToolFailureClassifier.classifyText("401 unauthorized: invalid api key"),
        )
    }

    @Test
    fun `cancellation and timeout are distinguished`() {
        assertEquals(ToolFailureKind.CANCELLED, ToolFailureClassifier.classifyText("job aborted by user"))
        assertEquals(ToolFailureKind.TIMEOUT, ToolFailureClassifier.classifyText("超时未完成"))
    }

    @Test
    fun `parameter errors are the last resort`() {
        assertEquals(
            ToolFailureKind.PARAMETER,
            ToolFailureClassifier.classifyText("invalid argument: path is required"),
        )
        assertEquals(ToolFailureKind.UNKNOWN, ToolFailureClassifier.classifyText("something odd happened"))
    }

    @Test
    fun `summary counts non unknown kinds`() {
        val text = ToolFailureClassifier.summarize(
            listOf(
                ToolFailureKind.NETWORK,
                ToolFailureKind.NETWORK,
                ToolFailureKind.PERMISSION,
                ToolFailureKind.UNKNOWN,
            )
        )
        assertEquals("网络×2、权限×1", text)
        assertEquals("", ToolFailureClassifier.summarize(listOf(ToolFailureKind.UNKNOWN)))
    }

    @Test
    fun `detail is clipped so a run event payload stays small`() {
        val long = "x".repeat(1_000)
        val failure = ToolFailureClassifier.detect("""{"error":"boom","detail":"$long"}""")
        assertTrue(failure!!.detail.length <= ToolFailureClassifier.MAX_DETAIL_CHARS)
    }
}
