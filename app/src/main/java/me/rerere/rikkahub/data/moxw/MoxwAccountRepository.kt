package me.rerere.rikkahub.data.moxw

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import java.io.IOException

/**
 * Owns the signed-in Moxw gateway account: the session, the derived API key, and the provider
 * row that actually carries that key into every chat request.
 *
 * Two invariants shape the whole class:
 *
 * 1. **Log in once, never again.** `/api/user/login` is rate-limited (nginx `login` zone) and
 *    the server caps live dashboard sessions at 50 per user, so a client that re-authenticated
 *    on every launch would lock its own user out. Everything after the first login goes through
 *    `/api/user/auth/refresh`, whose token rotates on every call — which is why the new value is
 *    written back under the same mutex that read it.
 *
 * 2. **The API key lives in the provider, not here.** The key is just an `sk-…` that the
 *    gateway can re-mint on demand, and it has to sit in `ProviderSetting.OpenAI.apiKey` for
 *    chat/backup/export to work unchanged. This class only remembers the *token id* so the same
 *    token is reused instead of piling up one per launch.
 */
class MoxwAccountRepository(
    private val store: MoxwCredentialStore,
    private val api: MoxwApi,
    private val settingsStore: SettingsStore,
    private val userAgent: String,
) {
    private val mutex = Mutex()

    private val _account = MutableStateFlow(store.read().account)
    val account: StateFlow<MoxwAccount?> = _account.asStateFlow()

    /** In-memory only: the JWT expires in 15 minutes and is cheap to mint again. */
    @Volatile
    private var access: MoxwAccessToken? = null

    val isSignedIn: Boolean get() = _account.value != null

    // ---------------------------------------------------------------- public surface

    /**
     * Signs in, then makes the account usable: mints/reuses an API key and writes it into the
     * Moxw provider together with the model list this account's group can actually reach.
     *
     * Returns the snapshot so the caller can render the card immediately without a round trip.
     */
    suspend fun signIn(username: String, password: String): MoxwAccountSnapshot = mutex.withLock {
        val login = api.login(username.trim(), password, userAgent)
        val account = MoxwAccount(
            userId = login.userId,
            username = login.username.ifBlank { username.trim() },
            displayName = login.displayName,
            refreshToken = login.refreshToken,
            group = login.group,
            quota = login.quota,
            usedQuota = login.usedQuota,
            sessionExpiresAt = login.sessionExpiresAt,
        )
        access = MoxwAccessToken(login.accessToken, 0L)
        persist(account)
        // The previous provider key belonged to whatever account was signed in before, so it
        // has to go before the new key is minted; otherwise the stale key would be reused.
        writeProviderKey(apiKey = "", models = emptyList())
        val snapshot = refreshUsableState(account)
        adoptDefaultModelIfUnset()
        snapshot
    }

    /**
     * Points the current assistant at the gateway's default model, but only when nothing has
     * been chosen yet.
     *
     * Guarded rather than unconditional because a user who deliberately picked another model
     * (or another provider) must not have that choice overwritten by a later sign-in.
     */
    private suspend fun adoptDefaultModelIfUnset() {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getCurrentAssistant()
        // Null means "follow the global default", which a fresh install leaves unset - that is
        // exactly the case this is here to fill in. A non-null id is the user's own choice.
        if (assistant.chatModelId != null) return
        val target = settings.providers
            .filterIsInstance<ProviderSetting.OpenAI>()
            .firstOrNull { it.id == MOXW_PROVIDER_ID }
            ?.models
            ?.firstOrNull { it.modelId == MOXW_DEFAULT_MODEL }
            ?: return
        settingsStore.updateAssistantModel(assistant.id, target.id)
    }

    /** Re-reads quota/group and re-resolves the key. Safe to call on page open. */
    suspend fun refreshSnapshot(): MoxwAccountSnapshot = mutex.withLock {
        val account = _account.value ?: throw MoxwAuthException("尚未登录")
        refreshUsableState(account)
    }

    /**
     * Sends the sign-up verification code.
     *
     * Deliberately not under [mutex]: it touches no account state, and a 2-per-30s rate limit
     * means the UI must be able to retry while a slow sign-in is still in flight.
     */
    suspend fun sendVerificationCode(email: String) {
        api.sendVerificationCode(email)
    }

    /** Creates the account. Does not sign in — the caller signs in explicitly afterwards. */
    suspend fun register(
        username: String,
        password: String,
        email: String,
        code: String,
    ) {
        api.register(username, password, email, code)
    }

    /**
     * The key every chat request uses. Reading it from the provider keeps a single source of
     * truth; if it is missing (fresh install, user cleared it) it is re-minted here.
     */
    suspend fun ensureApiKey(): String = mutex.withLock {
        val account = _account.value ?: throw MoxwAuthException("尚未登录")
        val existing = currentProvider()?.apiKey.orEmpty()
        if (existing.isNotBlank()) return@withLock existing
        val snapshot = refreshUsableState(account)
        currentProvider()?.apiKey?.takeIf { it.isNotBlank() }
            ?: throw MoxwAuthException("无法获取密钥，请重新登录")
    }

    /**
     * Signs out: revokes the server session, drops the local encrypted state, and clears the
     * provider key so the app stops sending requests under the old account.
     */
    suspend fun signOut() = mutex.withLock {
        val account = _account.value
        if (account != null) {
            val token = access?.value ?: runCatching { refreshAccess(account) }.getOrNull()
            if (token != null) api.logout(token, account.refreshToken)
        }
        access = null
        store.clear()
        _account.value = null
        writeProviderKey(apiKey = "", models = emptyList())
    }

    /** Adds the deepseek default to the freshly signed-in account's model list first. */
    private suspend fun refreshUsableState(account: MoxwAccount): MoxwAccountSnapshot {
        val token = refreshAccess(account)
        val key = reuseOrCreateKey(account, token)
        val models = runCatching { api.listModelIds(key) }.getOrElse { emptyList() }
        val updated = account.copy(tokenId = _account.value?.tokenId ?: account.tokenId)
        val provider = writeProviderKey(apiKey = key, models = models.ordered())
        return MoxwAccountSnapshot(
            account = updated,
            models = models,
            apiKeyPresent = provider?.apiKey?.isNotBlank() == true,
        )
    }

    /**
     * Reuses the token this app already created, minting one only when there is none.
     *
     * The server masks keys in the list, so the only way to tell "mine" from "the user made
     * this in the web console" is the token id recorded at creation time.
     */
    private suspend fun reuseOrCreateKey(account: MoxwAccount, accessToken: String): String {
        val recorded = _account.value?.tokenId ?: 0
        if (recorded > 0) {
            runCatching { return api.revealToken(accessToken, recorded) }
                .onFailure { Log.w(TAG, "Recorded token $recorded is gone, minting a new one", it) }
        }
        val before = runCatching { api.listTokens(accessToken) }.getOrDefault(emptyList())
        val beforeIds = before.map { it.id }.toSet()
        api.createToken(accessToken, TOKEN_NAME)
        val created = api.listTokens(accessToken)
            .firstOrNull { it.id !in beforeIds && it.name == TOKEN_NAME }
            ?: createdFallback(accessToken, before)
        val key = api.revealToken(accessToken, created.id)
        persist(_account.value?.copy(tokenId = created.id) ?: account.copy(tokenId = created.id))
        return key
    }

    /**
     * The create endpoint returns no id, so the new token is normally found by diffing the
     * list. If another device created one in the same window the diff may be ambiguous; taking
     * the newest matching row is still correct, it just may reuse a sibling token.
     */
    private suspend fun createdFallback(
        accessToken: String,
        before: List<MoxwApi.TokenSummary>,
    ): MoxwApi.TokenSummary =
        api.listTokens(accessToken)
            .filter { it.name == TOKEN_NAME }
            .maxByOrNull { it.id }
            ?: throw MoxwAuthException("创建密钥失败：服务器未返回新密钥")

    /** Returns a live JWT, refreshing (and persisting the rotated token) when needed. */
    private suspend fun refreshAccess(account: MoxwAccount): String {
        val cached = access
        if (cached != null && cached.expiresAtMillis > System.currentTimeMillis() + EXPIRY_SLACK_MS) {
            return cached.value
        }
        val result = api.refresh(account.refreshToken, userAgent)
        val refreshed = account.copy(
            refreshToken = result.refreshToken ?: account.refreshToken,
            quota = result.quota ?: account.quota,
            usedQuota = result.usedQuota ?: account.usedQuota,
            group = result.group ?: account.group,
        )
        persist(refreshed)
        access = MoxwAccessToken(result.accessToken, result.accessExpiresAt)
        return result.accessToken
    }

    // ---------------------------------------------------------------- provider plumbing

    private suspend fun currentProvider(): ProviderSetting.OpenAI? =
        settingsStore.settingsFlow.value.providers
            .filterIsInstance<ProviderSetting.OpenAI>()
            .firstOrNull { it.id == MOXW_PROVIDER_ID }

    /**
     * Writes key + model list into the Moxw provider row, preserving anything the user changed
     * by hand (custom headers, routing). Returns the row so the caller can read the key back
     * from the same source the chat path uses.
     */
    private suspend fun writeProviderKey(
        apiKey: String,
        models: List<String>,
    ): ProviderSetting.OpenAI? {
        var result: ProviderSetting.OpenAI? = null
        settingsStore.update { settings ->
            val providers = settings.providers.map { provider ->
                if (provider is ProviderSetting.OpenAI && provider.id == MOXW_PROVIDER_ID) {
                    // Keep user-made models, but let the gateway's list own the ones it serves:
                    // a model that disappeared from the group must disappear from the picker.
                    val userModels = provider.models.filter { it.modelId !in models }
                    val built = models.map { modelId ->
                        provider.models.firstOrNull { it.modelId == modelId }
                            ?: Model(
                                modelId = modelId,
                                displayName = modelId,
                                inputModalities = listOf(Modality.TEXT),
                                outputModalities = listOf(Modality.TEXT),
                                abilities = listOf(ModelAbility.TOOL),
                            )
                    }
                    // `copy`, not `copyProvider`: the latter deliberately carries only the
                    // fields every provider type shares, and `apiKey` is OpenAI-only.
                    provider.copy(
                        apiKey = apiKey,
                        enabled = apiKey.isNotBlank() || provider.enabled,
                        models = (built + userModels).distinctBy { it.modelId },
                    ).also { result = it }
                } else {
                    provider
                }
            }
            settings.copy(providers = providers)
        }
        return result
    }

    private suspend fun persist(account: MoxwAccount) {
        store.write(MoxwAccountState(account))
        _account.value = account
    }

    private companion object {
        const val TAG = "MoxwAccountRepository"

        /** Refresh a little before the JWT actually dies, so a request never races the expiry. */
        const val EXPIRY_SLACK_MS = 60_000L

        /** Fixed name so the token is recognisable in the web console and reusable after a
         *  reinstall (matched by name when the recorded id is gone). */
        const val TOKEN_NAME = "Moxw App"
    }
}

/**
 * Orders the gateway's model list so the default chat model comes first and the alternate
 * price tiers (`-特价` / `-满血` suffixes) do not crowd the top of the picker.
 */
internal fun List<String>.ordered(): List<String> {
    val priority = { id: String ->
        when {
            id == MOXW_DEFAULT_MODEL -> 0
            id.endsWith("-满血") || id.endsWith("-特价") -> 2
            else -> 1
        }
    }
    return sortedWith(compareBy({ priority(it) }, { it }))
}

/** Wraps transport failures so the UI never shows a raw `java.net.UnknownHostException`. */
internal fun Throwable.toUserMessage(): String = when (this) {
    is MoxwAuthException -> message ?: "操作失败"
    is IOException -> "网络不可用，请检查连接"
    else -> "操作失败：${message ?: this::class.java.simpleName}"
}

