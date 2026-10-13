package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.shizuku.ShizukuManager

private const val DEFAULT_TIMEOUT_MS = 30_000
private const val MIN_TIMEOUT_MS = 1_000

/** Matches ssh_exec / shizuku_exec's ceiling (300s / 5 min). */
private const val MAX_TIMEOUT_MS = 300_000

/**
 * Run a shell command with **root** privileges (uid 0) through the Shizuku / Sui broker.
 *
 * This is the explicit root counterpart to [shizukuExecTool]: it does not escalate anything
 * itself — the user's own root manager plus a root-started Shizuku (or Sui) provide the broker,
 * and the user service this app binds simply inherits that UID. When the broker is not running as
 * root the tool refuses with a structured `root_not_available` envelope rather than quietly running
 * the command as the shell UID.
 *
 * Approval-gated like every other shell surface, and additionally **blacklisted by default** — see
 * `ToolApprovalDefaults.DEFAULT_BLACKLISTED` — because the safe state for a tool that can do
 * anything at all is "ask first". It is also refused outright in headless runs (cron / sub-agent /
 * workflow), see `HeadlessToolApprovalPolicy.ROOT_TOOL_NAMES`.
 */
fun rootExecTool(context: Context): Tool = Tool(
    name = "root_exec",
    description = """
        Run a shell command as root (uid 0). Returns stdout, stderr, and exit code.

        Requires the Shizuku (or Sui) broker to be running with ROOT privileges: start Shizuku
        via root, or install the Sui module. If the broker is only running with the shell UID
        (the default when Shizuku is started over ADB or wireless debugging) this tool refuses
        with error root_not_available - use shizuku_exec for those commands instead. The current
        privilege level is shown in Settings -> Shizuku.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run as root, e.g. 'id' or 'ls -l /data/adb'")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in milliseconds. Default $DEFAULT_TIMEOUT_MS, max $MAX_TIMEOUT_MS.")
                })
            },
            required = listOf("command")
        )
    },
    execute = { input ->
        val command = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        if (command.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "command is required") }.toString()
                )
            )
        }
        val timeoutMs = (input.jsonObject["timeout_ms"]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        // Refuse rather than silently running as the shell UID: the caller asked for root, and
        // pretending it got it is how a "root" step quietly does nothing. Shizuku.getUid() reports
        // the broker's real UID (0 root / 2000 shell / -1 with no live, permitted binder), and this
        // app's user service runs with that same UID — see PrivilegeLevel for the on-device evidence.
        val level = ShizukuManager.privilege()
        if (!RootExecRules.isAvailable(level)) {
            return@Tool listOf(UIMessagePart.Text(RootExecRules.unavailableEnvelope(level).toString()))
        }
        val result = ShizukuManager.exec(context, command, timeoutMs)
        listOf(UIMessagePart.Text(result.toString()))
    }
)
