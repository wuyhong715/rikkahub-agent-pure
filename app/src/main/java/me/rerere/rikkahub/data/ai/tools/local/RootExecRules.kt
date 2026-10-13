package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.shizuku.PrivilegeLevel

/**
 * The pure decision table behind the `root_exec` tool: whether the broker can grant root at all,
 * and what to hand the model when it cannot.
 *
 * Kept out of [rootExecTool] (which needs a `Context` and the Shizuku SDK) so the envelope — the
 * only part a model ever reads — is unit-testable on a bare JVM, exactly like
 * [me.rerere.rikkahub.shizuku.ShizukuStatusMapper] for `shizuku_exec`.
 */
object RootExecRules {

    /** The `error` code the model sees when the broker's UID is not 0. */
    const val ERROR_NOT_AVAILABLE: String = "root_not_available"

    /**
     * Only a broker that actually reports uid 0 may run a command through `root_exec`. Everything
     * else — a shell-UID broker, or no live broker at all — is refused rather than silently
     * executed as the shell UID.
     */
    fun isAvailable(level: PrivilegeLevel): Boolean = level == PrivilegeLevel.ROOT

    /** The broker's actual identity, for the model to reason on: `root` / `shell` / `unknown`. */
    fun privilegeName(level: PrivilegeLevel): String = when (level) {
        PrivilegeLevel.ROOT -> "root"
        PrivilegeLevel.SHELL -> "shell"
        PrivilegeLevel.UNKNOWN -> "unknown"
    }

    /**
     * The structured refusal. It carries the broker's current privilege and how to change it, so
     * the model can stop retrying and say something actionable instead of "root_exec failed".
     */
    fun unavailableEnvelope(level: PrivilegeLevel): JsonObject = buildJsonObject {
        put("error", ERROR_NOT_AVAILABLE)
        put("privilege", privilegeName(level))
        put("recovery", recovery(level))
    }

    private fun recovery(level: PrivilegeLevel): String = when (level) {
        PrivilegeLevel.SHELL ->
            "The Shizuku (or Sui) broker is running as the shell UID (2000), so it cannot grant " +
                "root. Start Shizuku with root (Shizuku app -> Start (via root); the device must " +
                "already be rooted with KernelSU / Magisk / APatch), or install the Sui module, " +
                "then retry. Use shizuku_exec if the shell UID is enough for this command."
        PrivilegeLevel.ROOT ->
            // Not reachable via unavailableEnvelope's callers; kept so the mapping stays total.
            "The broker is running as root."
        PrivilegeLevel.UNKNOWN ->
            "No Shizuku / Sui broker is reachable. Install and start Shizuku, grant this app the " +
                "Shizuku permission from Settings -> Shizuku, then retry. Getting root also " +
                "requires starting the broker with root or installing the Sui module."
    }
}
