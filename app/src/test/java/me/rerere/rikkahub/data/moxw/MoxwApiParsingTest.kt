package me.rerere.rikkahub.data.moxw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parts of the gateway protocol that are easy to get subtly wrong and impossible to
 * notice from the UI: which `Set-Cookie` is the session, and what the balance expression is
 * actually computed from.
 *
 * Fixtures are real responses captured from `api.moxw.dpdns.org` (rc.45), trimmed.
 */
class MoxwApiParsingTest {

    @Test
    fun `login picks the refresh cookie and ignores the session hint`() {
        // The real login reply carries two Set-Cookie values. Reading the first one blindly
        // stores `new_api_has_session=1` and every later refresh 401s.
        val hasSession = "new_api_has_session=1; Path=/; Max-Age=2591999"
        val refresh = "new_api_refresh=90f493d6-6ef1-4887-a3e4-0ef70d5e653a.LWv7BBMslsxv59RAlTRXr7X59oVPaREfGonX3p5vrgKTTbms0CXH2PdclKMmzALK; Path=/api/user/auth; Expires=Sat, 28 Nov 2026 02:50:01 GMT; Max-Age=2591999; HttpOnly; SameSite=Strict"

        assertNull(MoxwApi.extractRefreshCookie(hasSession))
        assertEquals(
            "90f493d6-6ef1-4887-a3e4-0ef70d5e653a.LWv7BBMslsxv59RAlTRXr7X59oVPaREfGonX3p5vrgKTTbms0CXH2PdclKMmzALK",
            MoxwApi.extractRefreshCookie(refresh),
        )
    }

    @Test
    fun `an empty refresh cookie value is treated as absent`() {
        // Seen on logout: the server clears the cookie by sending it empty.
        assertNull(MoxwApi.extractRefreshCookie("new_api_refresh=; Path=/api/user/auth; Max-Age=0"))
    }

    @Test
    fun `quota converts at the gateway's own rate`() {
        // 500000 quota = 1 USD, and the gateway pegs 1 USD at 7.3 CNY.
        assertEquals(7.3, moxwQuotaToCny(500_000), 1e-9)
        assertEquals(1.0, moxwQuotaToCny(68_493), 0.01)
        assertEquals(0.0, moxwQuotaToCny(0), 1e-9)
    }

    @Test
    fun `the model list puts the default first and demotes alternate price tiers`() {
        val ordered = listOf(
            "glm-5.3",
            "gpt-6.1-sol-特价",
            "deepseek-v4.1-flash-1017",
            "gpt-6.1-sol",
            "kimi-k3.1-pro-满血",
        ).ordered()

        assertEquals(MOXW_DEFAULT_MODEL, ordered.first())
        // Tiers keep their relative order but never outrank a normal model.
        val tiers = ordered.filter { it.endsWith("-特价") || it.endsWith("-满血") }
        assertEquals(listOf("gpt-6.1-sol-特价", "kimi-k3.1-pro-满血"), tiers)
        assertTrue(ordered.indexOf("glm-5.3") < ordered.indexOf("gpt-6.1-sol-特价"))
    }

    @Test
    fun `the balance expression reads the user pool not the token pool`() {
        // An unlimited-quota token reports a negative `total_available`, so pointing the
        // expression at `data.total_available` would show a negative balance forever.
        assertTrue(MOXW_BALANCE_RESULT_PATH.startsWith("data.user_quota.total_available"))
        assertTrue(MOXW_BALANCE_API_PATH.startsWith("/api/"))
    }
}
