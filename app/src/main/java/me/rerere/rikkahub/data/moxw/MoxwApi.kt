package me.rerere.rikkahub.data.moxw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The subset of new-api's dashboard API this client uses, plus the parsing that turns its
 * envelopes into typed values.
 *
 * Everything here is a pure function of (request, response body) so the shapes can be pinned
 * by JVM tests without a server; [MoxwAccountRepository] owns the retry/rotation policy.
 */
class MoxwApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
) {
    /** A successful login: the refresh token plus what the account card shows. */
    data class LoginResult(
        val accessToken: String,
        val refreshToken: String,
        val userId: Int,
        val username: String,
        val displayName: String,
        val group: String,
        val quota: Int,
        val usedQuota: Int,
        val sessionExpiresAt: Long,
    )

    data class RefreshResult(
        val accessToken: String,
        val accessExpiresAt: Long,
        val refreshToken: String?,
        val quota: Int?,
        val usedQuota: Int?,
        val group: String?,
    )

    data class TokenSummary(val id: Int, val name: String, val status: Int)

    /**
     * POST /api/user/login
     *
     * The refresh token arrives as a `Set-Cookie` header rather than in the JSON body, so it
     * has to be read from the header directly. Its `Path` is `/api/user/auth`, which is why
     * the client carries it by hand instead of relying on a cookie jar.
     */
    suspend fun login(username: String, password: String, userAgent: String): LoginResult {
        val body = JsonObject(
            mapOf(
                "username" to JsonPrimitive(username),
                "password" to JsonPrimitive(password),
            )
        )
        val request = Request.Builder()
            .url("$baseUrl/api/user/login")
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .build()
        val response = client.newCall(request).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw MoxwAuthException(loginFailureMessage(response.code, text))
        }
        val data = parseEnvelope(text)["data"]?.jsonObject
            ?: throw MoxwAuthException("登录失败：服务器返回格式异常")
        val user = data["user"]?.jsonObject
            ?: throw MoxwAuthException("登录失败：服务器未返回账号信息")
        val refresh = response.headers.values("Set-Cookie")
            .firstNotNullOfOrNull { cookie -> extractRefreshCookie(cookie) }
            ?: throw MoxwAuthException("登录失败：服务器未下发会话凭据")
        return LoginResult(
            accessToken = data["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            refreshToken = refresh,
            userId = user["id"]?.jsonPrimitive?.intOrNull ?: 0,
            username = user["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            displayName = user["display_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            group = user["group"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            quota = user["quota"]?.jsonPrimitive?.intOrNull ?: 0,
            usedQuota = user["used_quota"]?.jsonPrimitive?.intOrNull ?: 0,
            sessionExpiresAt = data["session"]?.jsonObject
                ?.get("expires_at")?.jsonPrimitive?.longOrNull
                ?.times(1000L) ?: 0L,
        )
    }

    /**
     * POST /api/user/auth/refresh
     *
     * Rotates the refresh token: the reply carries a new `Set-Cookie` and the old value stops
     * working. Callers MUST persist [RefreshResult.refreshToken] when it is non-null.
     */
    suspend fun refresh(refreshToken: String, userAgent: String): RefreshResult {
        val request = Request.Builder()
            .url("$baseUrl/api/user/auth/refresh")
            .post(EMPTY_BODY)
            .header("Content-Type", "application/json")
            .header("Cookie", "${REFRESH_COOKIE_NAME}=$refreshToken")
            .header("User-Agent", userAgent)
            .build()
        val response = client.newCall(request).await()
        val text = response.body.string()
        if (response.code == 401 || response.code == 403) {
            throw MoxwAuthException("登录已过期，请重新登录")
        }
        if (!response.isSuccessful) {
            throw MoxwAuthException("刷新登录状态失败（HTTP ${response.code}）")
        }
        val data = parseEnvelope(text)["data"]?.jsonObject
            ?: throw MoxwAuthException("刷新登录状态失败：服务器返回格式异常")
        val rotated = response.headers.values("Set-Cookie")
            .firstNotNullOfOrNull { cookie -> extractRefreshCookie(cookie) }
        val user = data["user"]?.jsonObject
        return RefreshResult(
            accessToken = data["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            accessExpiresAt = data["access_expires_at"]?.jsonPrimitive?.longOrNull
                ?.times(1000L) ?: 0L,
            refreshToken = rotated,
            quota = user?.get("quota")?.jsonPrimitive?.intOrNull,
            usedQuota = user?.get("used_quota")?.jsonPrimitive?.intOrNull,
            group = user?.get("group")?.jsonPrimitive?.contentOrNull,
        )
    }

    /** POST /api/user/auth/logout — best effort; a dead session is already logged out. */
    suspend fun logout(accessToken: String, refreshToken: String) {
        val request = Request.Builder()
            .url("$baseUrl/api/user/auth/logout")
            .post(EMPTY_BODY)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .header("Cookie", "${REFRESH_COOKIE_NAME}=$refreshToken")
            .build()
        runCatching { client.newCall(request).await().close() }
    }

    /**
     * GET /api/token/?p=1&size=100 with a dashboard JWT.
     *
     * The list is masked (`iBTW**********XUnj`), so it can only be searched for an existing
     * token by id and name — the key itself comes from [revealToken].
     */
    suspend fun listTokens(accessToken: String): List<TokenSummary> {
        val request = Request.Builder()
            .url("$baseUrl/api/token/?p=1&size=100")
            .get()
            .header("Authorization", "Bearer $accessToken")
            .build()
        val response = client.newCall(request).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw MoxwAuthException("读取密钥列表失败（HTTP ${response.code}）")
        }
        val items = parseEnvelope(text)["data"]?.jsonObject?.get("items")?.jsonArray
            ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            TokenSummary(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                status = obj["status"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }

    /** POST /api/token/ — creates a token. The reply has no id and no key. */
    suspend fun createToken(accessToken: String, name: String) {
        val body = JsonObject(
            mapOf(
                "name" to JsonPrimitive(name),
                "expired_time" to JsonPrimitive(-1),
                "unlimited_quota" to JsonPrimitive(true),
                "remain_quota" to JsonPrimitive(0),
                "model_limits_enabled" to JsonPrimitive(false),
                // Empty group = "follow the user's own group", so a later plan change on the
                // server keeps working without the app touching the token again.
                "group" to JsonPrimitive(""),
            )
        )
        val request = Request.Builder()
            .url("$baseUrl/api/token/")
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .build()
        val response = client.newCall(request).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw MoxwAuthException(describeApiError(text) ?: "创建密钥失败（HTTP ${response.code}）")
        }
    }

    /** POST /api/token/{id}/key — the one endpoint that hands back a full `sk-…`. */
    suspend fun revealToken(accessToken: String, tokenId: Int): String {
        val request = Request.Builder()
            .url("$baseUrl/api/token/$tokenId/key")
            .post(EMPTY_BODY)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .build()
        val response = client.newCall(request).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw MoxwAuthException(describeApiError(text) ?: "读取密钥失败（HTTP ${response.code}）")
        }
        return parseEnvelope(text)["data"]?.jsonObject
            ?.get("key")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: throw MoxwAuthException("读取密钥失败：服务器未返回密钥")
    }

    /**
     * GET /api/verification?email=… — sends the 6-character code that [register] requires.
     *
     * The server rate-limits this to 2 per 30s per IP and refuses an address that is already
     * bound to an account, which is what makes "one mailbox, one account" hold: the second
     * sign-up never gets a code.
     */
    suspend fun sendVerificationCode(email: String) {
        val encoded = java.net.URLEncoder.encode(email.trim(), "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl/api/verification?email=$encoded")
            .get()
            .build()
        val response = client.newCall(request).await()
        val text = response.body.string()
        val message = describeApiError(text)
        if (!response.isSuccessful || message != null) {
            throw MoxwAuthException(message ?: "发送验证码失败（HTTP ${response.code}）")
        }
    }

    /** POST /api/user/register — needs the code [sendVerificationCode] mailed out. */
    suspend fun register(username: String, password: String, email: String, code: String) {
        val body = JsonObject(
            mapOf(
                "username" to JsonPrimitive(username.trim()),
                "password" to JsonPrimitive(password),
                "email" to JsonPrimitive(email.trim()),
                "verification_code" to JsonPrimitive(code.trim()),
            )
        )
        val request = Request.Builder()
            .url("$baseUrl/api/user/register")
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
        val response = client.newCall(request).await()
        val text = response.body.string()
        val message = describeApiError(text)
        // The gateway answers 200 with `success:false` for a rejected sign-up, so the body
        // decides, not the status code.
        val succeeded = runCatching {
            json.parseToJsonElement(text).jsonObject["success"]?.jsonPrimitive
                ?.booleanOrNull == true
        }.getOrDefault(false)
        if (!response.isSuccessful || message != null || !succeeded) {
            throw MoxwAuthException(message ?: "注册失败（HTTP ${response.code}）")
        }
    }

    /** GET /v1/models with the API key: exactly the models this user's group can reach. */
    suspend fun listModelIds(apiKey: String): List<String> {
        val request = Request.Builder()
            .url("$baseUrl/v1/models")
            .get()
            .header("Authorization", "Bearer $apiKey")
            .build()
        val response = client.newCall(request).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw MoxwAuthException("读取模型列表失败（HTTP ${response.code}）")
        }
        return json.parseToJsonElement(text).jsonObject["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
    }

    private fun parseEnvelope(text: String): JsonObject =
        runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw MoxwAuthException("服务器返回了无法解析的响应") }

    private fun loginFailureMessage(code: Int, text: String): String {
        // 429 from nginx's login zone and 409 from new-api's own session cap are the two the
        // user can actually act on, so they get their own wording.
        if (code == 429) return "尝试过于频繁，请稍后再试"
        if (code == 409) return "登录会话数已达上限，请稍后再试"
        val apiMessage = describeApiError(text)
        return when {
            apiMessage != null -> apiMessage
            code == 401 || code == 400 -> "用户名或密码错误"
            else -> "登录失败（HTTP $code）"
        }
    }

    private fun describeApiError(text: String): String? = runCatching {
        val envelope = json.parseToJsonElement(text).jsonObject
        envelope["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(JSON)
        const val REFRESH_COOKIE_NAME = "new_api_refresh"

        /**
         * Picks the refresh token out of one `Set-Cookie` value.
         *
         * The header also carries `new_api_has_session=1`, so matching on the cookie *name*
         * matters: a naive "first cookie" read would store `new_api_has_session` and every
         * refresh would then 401.
         */
        fun extractRefreshCookie(setCookie: String): String? {
            val pair = setCookie.substringBefore(';').trim()
            val name = pair.substringBefore('=', missingDelimiterValue = "")
            if (name != REFRESH_COOKIE_NAME) return null
            return pair.substringAfter('=', missingDelimiterValue = "").takeIf { it.isNotBlank() }
        }
    }
}
