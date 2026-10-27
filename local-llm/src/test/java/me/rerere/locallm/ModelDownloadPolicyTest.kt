package me.rerere.locallm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the retry loop, pinned without a socket.
 *
 * [ModelInstallTest] proves the loop *uses* these rules; this file proves the rules themselves -
 * which hosts, in which order, and which failures are worth another request.
 */
class ModelDownloadPolicyTest {

    private val hfUrl = "https://huggingface.co/org/repo/resolve/main/model.gguf"

    @Test
    fun `a hub URL gets the mirror as its second candidate`() {
        val candidates = ModelDownloadPolicy.candidates(hfUrl, mirrorEnabled = true)

        assertEquals(2, candidates.size)
        assertEquals(ModelDownloadPolicy.PRIMARY_HOST, candidates[0].host)
        assertEquals(hfUrl, candidates[0].url)
        assertEquals(ModelDownloadPolicy.MIRROR_HOST, candidates[1].host)
        assertEquals(
            "https://hf-mirror.com/org/repo/resolve/main/model.gguf",
            candidates[1].url,
        )
        assertFalse(candidates[0].isMirror)
        assertTrue(candidates[1].isMirror)
    }

    @Test
    fun `the mirror can be turned off`() {
        val candidates = ModelDownloadPolicy.candidates(hfUrl, mirrorEnabled = false)

        assertEquals(listOf(hfUrl), candidates.map { it.url })
    }

    @Test
    fun `a mirror URL falls back to the primary host`() {
        // Read the same rule backwards: a user who pasted an hf-mirror link on a network where
        // the mirror is the unreachable one still gets one working host tried.
        val candidates = ModelDownloadPolicy.candidates(
            "https://hf-mirror.com/org/repo/resolve/main/model.gguf",
            mirrorEnabled = true,
        )

        assertEquals(
            listOf("hf-mirror.com", "huggingface.co"),
            candidates.map { it.host },
        )
        assertEquals(
            "https://huggingface.co/org/repo/resolve/main/model.gguf",
            candidates[1].url,
        )
    }

    @Test
    fun `a third-party URL is never redirected elsewhere`() {
        val url = "https://example.com/models/model.gguf"
        assertEquals(listOf(url), ModelDownloadPolicy.candidates(url, mirrorEnabled = true).map { it.url })
    }

    @Test
    fun `host rewrite keeps the path, the query and an explicit port`() {
        assertEquals(
            "https://hf-mirror.com/a/b/resolve/main/m.gguf?download=true",
            ModelDownloadPolicy.withHost(
                "https://huggingface.co/a/b/resolve/main/m.gguf?download=true",
                ModelDownloadPolicy.MIRROR_HOST,
            ),
        )
        assertEquals(
            "https://hf-mirror.com:443/a",
            ModelDownloadPolicy.withHost("https://huggingface.co:443/a", ModelDownloadPolicy.MIRROR_HOST),
        )
    }

    @Test
    fun `withHost refuses a URL that has no authority`() {
        assertEquals(null, ModelDownloadPolicy.withHost("model.gguf", ModelDownloadPolicy.MIRROR_HOST))
        assertEquals(null, ModelDownloadPolicy.withHost("https://", ModelDownloadPolicy.MIRROR_HOST))
    }

    @Test
    fun `hostOf strips scheme, port, user info and path`() {
        assertEquals("huggingface.co", ModelDownloadPolicy.hostOf("https://huggingface.co/x/y"))
        assertEquals("huggingface.co", ModelDownloadPolicy.hostOf("https://user:pw@huggingface.co:443/x"))
        assertEquals("hf-mirror.com", ModelDownloadPolicy.hostOf("https://hf-mirror.com?x=1"))
        assertEquals("", ModelDownloadPolicy.hostOf("not a url"))
    }

    @Test
    fun `server-side and throttling codes are transient, request-specific ones are not`() {
        listOf(500, 502, 503, 504, 408, 429, 425).forEach { code ->
            assertTrue("HTTP $code should be retried", ModelDownloadPolicy.isTransientHttpCode(code))
        }
        listOf(400, 401, 403, 404, 410, 416, 451).forEach { code ->
            assertFalse(
                "HTTP $code answers the same to the fourth attempt; retrying only delays the message",
                ModelDownloadPolicy.isTransientHttpCode(code),
            )
        }
    }

    @Test
    fun `the backoff grows and never goes backwards`() {
        val delays = (2..ModelDownloadPolicy.MAX_ATTEMPTS).map { ModelDownloadPolicy.backoffMillis(it) }
        assertTrue("every retry must wait, or the same broken connection is reused", delays.all { it > 0 })
        assertEquals(delays.sorted(), delays)
        assertTrue("the ladder must stay under a minute so retries are not perceived as a hang",
            delays.sum() < 60_000)
    }
}
