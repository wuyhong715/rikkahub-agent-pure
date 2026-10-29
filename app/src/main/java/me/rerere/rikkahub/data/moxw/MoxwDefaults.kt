package me.rerere.rikkahub.data.moxw

import kotlin.uuid.Uuid

/**
 * Stable identity for the bundled Moxw gateway provider.
 *
 * Fixed rather than random so that (a) the seeded row survives reinstalls and does not get
 * re-added under a new id after a settings restore, and (b) the account repository can find
 * "the" provider to write the key into without guessing by name.
 */
val MOXW_PROVIDER_ID: Uuid = Uuid.parse("6d1f4a2c-3b7e-4c58-9f01-8a2d5e6c7b90")

/** Server root (no /v1). Dashboard endpoints hang off this. */
const val MOXW_API_BASE: String = "https://api.moxw.dpdns.org"

/** Where the gateway lives. Kept as a constant, not user-editable, so support can rely on it. */
const val MOXW_BASE_URL: String = "$MOXW_API_BASE/v1"

/**
 * The model a freshly signed-in account starts on: the cheapest capable model on the
 * gateway, so a new user's ¥1 trial goes a long way and the first reply is fast.
 */
const val MOXW_DEFAULT_MODEL: String = "deepseek-v4.1-flash-1017"

/**
 * Where the remaining balance comes from: `GET /api/usage/token/` with the API key.
 *
 * `total_available` on the token is meaningless for an unlimited-quota token (it goes
 * negative), so the user-level pool is what gets shown, converted to CNY. 500000 quota = $1
 * and the gateway pegs $1 at CNY 7.3, which is the same arithmetic its own console uses.
 */
const val MOXW_BALANCE_API_PATH: String = "/api/usage/token/"
const val MOXW_BALANCE_RESULT_PATH: String = "data.user_quota.total_available / 500000 x 7.3"

/**
 * User-Agent sent with the dashboard calls.
 *
 * Not cosmetic: the gateway records it in the login-session list, so a user looking at
 * "where am I logged in" in the web console can tell the app's sessions from browser ones.
 */
fun moxwUserAgent(androidVersion: String): String = "MoxwApp/Android $androidVersion"

/**
 * Gateway quota units to CNY.
 *
 * `QuotaPerUnit` is 500000 per USD and the gateway pegs USD at 7.3, which is the same
 * arithmetic its own console and pricing page use. Kept as one function so the account card
 * and any future balance display cannot drift apart.
 */
fun moxwQuotaToCny(quota: Int): Double = quota / 500_000.0 * 7.3
