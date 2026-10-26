package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

private const val LAUNCH_DEFAULT_WAIT_MS = 2_000
private const val LAUNCH_MAX_WAIT_MS = 8_000
private const val LAUNCH_POLL_MS = 250L

/** Default node cap matches read_window_tree so the tree is directly usable. */
private const val LAUNCH_DEFAULT_MAX_NODES = 300

/**
 * T-13 — launch, wait for the app to actually be up, and return its screen.
 *
 * `launch_app` only reports that the launch intent was dispatched: the app may still be
 * starting, may have crashed, or may never reach the foreground. Screen automation that taps
 * on the old screen is the most expensive failure this app has, so the wait is made part of
 * the launch instead of being left to a guessed pause.
 */
fun launchAndReadTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "launch_and_read",
    description = """
        Launch an installed app and return its screen once it is actually up.

        launch_app only reports that the launch intent was dispatched — the app may still be
        starting, may have crashed, or may never have come to the foreground. This tool waits
        for the target package to be foreground and for the node tree to stop changing, then
        returns that tree (node_id values usable by click_node / set_text) with how long it took
        to settle.

        Read checks before acting: ui_settled=false means the screen was still moving, so read
        the tree again rather than tapping; foreground_matches=false means the app did not come
        up, so do not tap blind.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Application package id, e.g. com.tencent.mm")
                })
                put("wait_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "How long to wait for the UI to settle (200-8000, default 2000)")
                })
                put("max_nodes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Node cap for the returned tree (default 300, max 2000)")
                })
            },
            required = listOf("package_name"),
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull?.trim()
        if (packageName.isNullOrBlank()) {
            return@Tool fmTextPart(fmErrEnvelope("missing_package", "package_name is required"))
        }
        val waitMs = (params["wait_ms"]?.jsonPrimitive?.intOrNull ?: LAUNCH_DEFAULT_WAIT_MS)
            .coerceIn(200, LAUNCH_MAX_WAIT_MS)
        val maxNodes = (params["max_nodes"]?.jsonPrimitive?.intOrNull ?: LAUNCH_DEFAULT_MAX_NODES)
            .coerceIn(1, 2000)

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return@Tool fmTextPart(
                buildJsonObject {
                    put("error", "app_not_launchable")
                    put("package", packageName)
                    put(
                        "recovery",
                        "The package has no launch activity or is not installed. " +
                            "Use list_installed_apps to confirm the package name.",
                    )
                }.toString()
            )
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val dispatched = runCatching { context.startActivity(launchIntent) }.isSuccess
        if (!dispatched) {
            return@Tool fmTextPart(
                buildJsonObject {
                    put("error", "launch_failed")
                    put("package", packageName)
                    put(
                        "recovery",
                        "The system refused the launch intent; open the app manually once, then retry.",
                    )
                }.toString()
            )
        }

        // One reader serves both the settle check and the final tree, so the model gets node_id
        // values from the same snapshot machinery it already knows.
        val reader = readWindowTreeTool(invocationContext)
        suspend fun readTree(maxNodes: Int, full: Boolean): JsonObject? {
            val parts = runCatching {
                reader.execute(
                    buildJsonObject {
                        put("max_nodes", maxNodes)
                        put("full", full)
                    }
                )
            }.getOrNull() ?: return null
            val text = (parts.firstOrNull() as? UIMessagePart.Text)?.text ?: return null
            return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        }

        val startedAt = System.currentTimeMillis()
        var previousHash: String? = null
        var settled = false
        var settledMs = 0L
        var lastForeground: String? = null

        while (System.currentTimeMillis() - startedAt < waitMs) {
            val current = readTree(maxNodes = 1, full = false)
            val hash = current?.get("tree_hash")?.jsonPrimitive?.contentOrNull
            val foreground = current?.get("package")?.jsonPrimitive?.contentOrNull
            if (foreground != null) lastForeground = foreground
            if (hash != null && hash == previousHash && foreground == packageName) {
                settled = true
                settledMs = System.currentTimeMillis() - startedAt
                break
            }
            previousHash = hash
            delay(LAUNCH_POLL_MS)
        }

        val tree = readTree(maxNodes = maxNodes, full = true)
        val foreground = tree?.get("package")?.jsonPrimitive?.contentOrNull ?: lastForeground
        val noWindow = tree?.get("error")?.jsonPrimitive?.contentOrNull == "no_active_window"

        val payload = buildJsonObject {
            put("package", packageName)
            put("launched", true)
            foreground?.let { put("foreground", it) }
            put("settled", settled)
            put("settled_ms", settledMs)
            if (noWindow) {
                put("screen", "no_active_window")
                put(
                    "next",
                    "The app launched but no accessibility window is exposed yet. Wait and read " +
                        "again, or take a screenshot to see what is actually on screen.",
                )
            }
            putChecks(
                listOf(
                    ResultCheck("launch_dispatched", true, "intent accepted for $packageName"),
                    ResultCheck(
                        "foreground_matches",
                        foreground == packageName,
                        foreground?.let { "foreground=$it" } ?: "no foreground package reported",
                    ),
                    ResultCheck(
                        "ui_settled",
                        settled,
                        if (settled) {
                            "tree stable after ${settledMs}ms"
                        } else {
                            "tree still changing after ${waitMs}ms"
                        },
                    ),
                )
            )
            tree?.let { put("tree", it) }
        }
        fmTextPart(payload.toString())
    },
)
