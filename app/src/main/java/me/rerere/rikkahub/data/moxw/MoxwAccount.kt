package me.rerere.rikkahub.data.moxw

import kotlinx.serialization.Serializable

/**
 * One signed-in Moxw gateway account.
 *
 * This is the whole of what the app knows about the user on the server: a username, a
 * refresh token that can mint fresh access tokens, and the derived state (group, quota) that
 * the UI shows. The API key the chat requests actually use is *not* stored here — it is
 * written into the provider ([MOXW_PROVIDER_ID])'s `apiKey`, exactly like a key the user
 * pasted by hand, so backup/export/restore keep working unchanged.
 */
@Serializable
data class MoxwAccount(
    /** Server-side user id. Stable across renames, so it is the identity. */
    val userId: Int,
    val username: String,
    val displayName: String = "",
    /**
     * Rotating refresh token. The server invalidates the previous value on every refresh
     * (`/api/user/auth/refresh` answers with a fresh `Set-Cookie`), so this must be written
     * back after every call or the next one fails with 401.
     */
    val refreshToken: String,
    /** User group on the gateway, e.g. `default` or `vip`. Shown in the account card. */
    val group: String = "",
    /** Remaining quota in gateway units (500000 = $1 at the current QuotaPerUnit). */
    val quota: Int = 0,
    val usedQuota: Int = 0,
    /** Epoch millis at which [refreshToken] stops working. */
    val sessionExpiresAt: Long = 0L,
    /** Id of the API token this app created on the server, so it can be reused instead of
     *  minting a new one on every launch. Zero means "not created yet". */
    val tokenId: Int = 0,
    val signedInAt: Long = System.currentTimeMillis(),
)

@Serializable
data class MoxwAccountState(
    val account: MoxwAccount? = null,
)

/** A live access token and the moment it dies. Never persisted: it is cheap to mint. */
data class MoxwAccessToken(
    val value: String,
    val expiresAtMillis: Long,
)

/**
 * Everything the account page needs to render one row, fetched in a single pass so the UI
 * never shows a half-updated card.
 */
data class MoxwAccountSnapshot(
    val account: MoxwAccount,
    val models: List<String>,
    val apiKeyPresent: Boolean,
)

/** Failure carrying a message that is already safe to show to the user. */
class MoxwAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
