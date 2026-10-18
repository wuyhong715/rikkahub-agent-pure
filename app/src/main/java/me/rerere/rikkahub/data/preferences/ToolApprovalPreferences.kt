package me.rerere.rikkahub.data.preferences
import me.rerere.rikkahub.runtime.tools.ApprovalMode

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.runtime.tools.ToolApprovalDefaults

private const val TAG = "ToolApprovalPreferences"

/** Workspace tools (created by createWorkspaceTools) are per-workspace, never
 *  per-app, so this prefix must never be written to or read from the global
 *  blacklist. */
private const val WORKSPACE_TOOL_PREFIX = "workspace_"

private val Context.toolApprovalDataStore by preferencesDataStore(name = "tool_approval")

/** True iff [name] is a workspace tool (reserved "workspace_" prefix, see
 *  createWorkspaceTools). Pure so the prefix rule can be unit-tested without a
 *  DataStore instance. */
internal fun isWorkspaceToolName(name: String): Boolean = name.startsWith(WORKSPACE_TOOL_PREFIX)

/** Splits [stored] into (kept, removed) where removed is every workspace_-prefixed
 *  entry. Pure - backs the read-time filter that keeps workspace tools out of the
 *  global blacklist. */
internal fun migrateWorkspaceToolsFrom(stored: Set<String>): Pair<Set<String>, Set<String>> {
    val removed = stored.filterTo(mutableSetOf(), ::isWorkspaceToolName)
    return (stored - removed) to removed
}

/**
 * The approval decision's persisted inputs, read in one go so a single tool call does not pay
 * two DataStore round-trips.
 *
 * @property blacklist tools that require a fresh confirmation before running. In Semi mode
 *   these ask; in Auto they are ignored; in Manual every tool asks regardless. Starts at
 *   [ToolApprovalDefaults.DEFAULT_BLACKLISTED] and is whatever the user made of it afterwards.
 * @property mode the posture level ([ApprovalMode]). Default [ApprovalMode.MANUAL] on a fresh
 *   install or after upgrade from the old YOLO scheme — the safest posture, so the user opts
 *   *out* of friction deliberately rather than discovering it was off.
 */
data class ToolApprovalSnapshot(
    val blacklist: Set<String> = emptySet(),
    val mode: ApprovalMode = ApprovalMode.MANUAL,
)

/**
 * Persistent tool-approval prefs, mode era. Two pieces:
 *
 * 1. **Blacklist** — the user's list of tools that must ask before running. Only consulted in
 *    Semi mode; in Auto it is ignored, in Manual every tool asks anyway. It starts as
 *    [ToolApprovalDefaults.DEFAULT_BLACKLISTED] — the root tool — so the default posture is
 *    "only the tool that can do anything asks". Configured from Settings → Tool approvals; each
 *    entry can be removed there to stop it asking (a dangerous default goes through an explicit
 *    confirmation first). The non-waivable floor
 *    ([me.rerere.rikkahub.runtime.tools.ToolApprovalDefaults.NO_ALWAYS_ALLOW]) asks regardless
 *    of this set and regardless of the mode; opaque relayed `mcp__*` tools ask too, except in
 *    Auto mode which waives them (拍板 2026-10-16).
 *
 * 2. **Approval mode** — the three-level posture selector ([ApprovalMode]). Replaces the old
 *    single-boolean YOLO toggle. The retired `global_auto_approve_yolo` key is no longer read;
 *    a pre-existing value is simply ignored (the mode defaults to MANUAL), so upgrading from
 *    the YOLO scheme resets every user to the safest posture.
 *
 * The companion in-memory "Allow for this chat" allow-list lives in
 * [me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList] and resets on /new or app
 * process restart.
 */
class ToolApprovalPreferences(private val context: Context) {
    private val store = context.toolApprovalDataStore
    private val K_BLACKLIST = stringSetPreferencesKey("tool_blacklist")
    @Suppress("unused")
    private val K_GLOBAL_YOLO = booleanPreferencesKey("global_auto_approve_yolo") // retired
    private val K_MODE = stringPreferencesKey("approval_mode")

    /**
     * Whether [ToolApprovalDefaults.DEFAULT_BLACKLISTED] has been merged into [K_BLACKLIST] yet.
     *
     * Until it has, every read unions the defaults in ([ToolApprovalDefaults.effectiveBlacklist]),
     * so a clean install already shows the root tool as blacklisted in Settings and — more to the
     * point — the runtime decision agrees with it. The first explicit edit materialises that view
     * and flips this flag; from then on the stored set is the whole truth, which is what lets a
     * user actually remove a default.
     */
    private val K_DEFAULT_BLACKLIST_SEEDED = booleanPreferencesKey("default_blacklist_seeded")

    // Filtered on every read: a stale workspace_-prefixed entry must never surface in the
    // settings list or an approval check, even before any cleanup write happens. The
    // blacklisted-by-default tools are unioned in until the first edit materialises them.
    val blacklistFlow: Flow<Set<String>> = store.data.map { prefs ->
        ToolApprovalDefaults.effectiveBlacklist(
            migrateWorkspaceToolsFrom(prefs[K_BLACKLIST].orEmpty()).first,
            prefs[K_DEFAULT_BLACKLIST_SEEDED] == true,
        )
    }

    /** Live flow of the current approval mode. Default [ApprovalMode.MANUAL]. */
    val modeFlow: Flow<ApprovalMode> = store.data.map { snapshotMode(it[K_MODE]) }

    suspend fun currentBlacklist(): Set<String> = snapshot().blacklist

    /**
     * Blacklist + mode in one read, for the per-call auto-approval check.
     *
     * Also the one-shot point where the [ToolApprovalDefaults.DEFAULT_BLACKLISTED] entries get
     * materialised: the first call after install merges them into storage once, so the runtime
     * decision matches what the Settings screen already showed. Every later call is a plain read.
     */
    suspend fun snapshot(): ToolApprovalSnapshot {
        val prefs = store.data.first()
        val seeded = prefs[K_DEFAULT_BLACKLIST_SEEDED] == true
        val blacklist = ToolApprovalDefaults.effectiveBlacklist(
            migrateWorkspaceToolsFrom(prefs[K_BLACKLIST].orEmpty()).first,
            seeded,
        )
        if (!seeded) {
            store.edit {
                it[K_BLACKLIST] = blacklist
                it[K_DEFAULT_BLACKLIST_SEEDED] = true
            }
        }
        return ToolApprovalSnapshot(blacklist = blacklist, mode = snapshotMode(prefs[K_MODE]))
    }

    /** Snapshot read of the mode alone. */
    suspend fun currentMode(): ApprovalMode = modeFlow.first()

    suspend fun setMode(mode: ApprovalMode) {
        store.edit { it[K_MODE] = mode.name }
    }

    /** Put [toolName] on the blacklist ([needsApproval] = true) or take it off. */
    suspend fun setBlacklisted(toolName: String, needsApproval: Boolean) {
        if (isWorkspaceToolName(toolName)) {
            Log.w(TAG, "refusing to put workspace tool '$toolName' on the global blacklist")
            return
        }
        store.edit { prefs ->
            // Materialise the defaults we were already showing *before* applying the change, so
            // removing one sticks: the stored set becomes authoritative from here on.
            val base = ToolApprovalDefaults.effectiveBlacklist(
                migrateWorkspaceToolsFrom(prefs[K_BLACKLIST].orEmpty()).first,
                prefs[K_DEFAULT_BLACKLIST_SEEDED] == true,
            )
            prefs[K_BLACKLIST] = if (needsApproval) base + toolName else base - toolName
            prefs[K_DEFAULT_BLACKLIST_SEEDED] = true
        }
    }

    /**
     * Drop every user-added blacklist entry. The floor and `mcp__*` tools still ask, and so do the
     * [ToolApprovalDefaults.DEFAULT_BLACKLISTED] tools: a bulk clear must not be a shortcut around
     * the per-tool risk confirmation that removing a dangerous default goes through.
     */
    suspend fun clearBlacklist() {
        store.edit {
            it[K_BLACKLIST] = ToolApprovalDefaults.DEFAULT_BLACKLISTED
            it[K_DEFAULT_BLACKLIST_SEEDED] = true
        }
    }

    /** Add every name in [toolNames] (workspace tools filtered out) to the blacklist in one write. */
    suspend fun addAllToBlacklist(toolNames: Collection<String>) {
        val clean = toolNames.filterNot(::isWorkspaceToolName).toSet()
        if (clean.isEmpty()) return
        store.edit { prefs ->
            val base = ToolApprovalDefaults.effectiveBlacklist(
                migrateWorkspaceToolsFrom(prefs[K_BLACKLIST].orEmpty()).first,
                prefs[K_DEFAULT_BLACKLIST_SEEDED] == true,
            )
            prefs[K_BLACKLIST] = base + clean
            prefs[K_DEFAULT_BLACKLIST_SEEDED] = true
        }
    }

    /** Resolve a stored mode string to [ApprovalMode], defaulting to [ApprovalMode.MANUAL]. */
    private fun snapshotMode(stored: String?): ApprovalMode =
        stored?.let { runCatching { ApprovalMode.valueOf(it) }.getOrNull() } ?: ApprovalMode.MANUAL
}
