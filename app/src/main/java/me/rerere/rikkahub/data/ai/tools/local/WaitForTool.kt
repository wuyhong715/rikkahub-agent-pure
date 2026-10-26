package me.rerere.rikkahub.data.ai.tools.local

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.service.ActionLogEntry
import me.rerere.rikkahub.service.RikkaAccessibilityService

private const val DEFAULT_TIMEOUT_MS = 5_000L

/**
 * Upper bound. A tool call that hangs for half a minute reads as a frozen app, and the model can
 * always call wait_for again — so cap well below any transport timeout.
 */
private const val MAX_TIMEOUT_MS = 15_000L

/**
 * Poll interval. Long enough that the tree walk (~20ms on a 300-node screen) is not the dominant
 * cost, short enough that a fast transition is not missed by a whole second.
 */
private const val POLL_INTERVAL_MS = 120L

/** Whether [value] satisfies the needle, case-insensitively for a substring match. */
private fun matchesNeedle(value: String?, needle: String, exact: Boolean): Boolean {
    if (value.isNullOrEmpty()) return false
    return if (exact) value == needle else value.contains(needle, ignoreCase = true)
}

/**
 * True when some visible node carries [needle] in its text or content description.
 *
 * The walk stops on the first hit (cap=1 makes traverseTree truncate and break), so a screen that
 * contains the needle costs a fraction of a screen that does not.
 */
private fun treeContains(
    svc: RikkaAccessibilityService,
    root: android.view.accessibility.AccessibilityNodeInfo,
    needle: String,
    exact: Boolean,
): Boolean {
    var found = false
    svc.traverseTree(
        root = root,
        filter = { n, _ ->
            n.isVisibleToUser &&
                (matchesNeedle(n.text?.toString(), needle, exact) ||
                    matchesNeedle(n.contentDescription?.toString(), needle, exact))
        },
        cap = 1,
        emit = { _, _, _ -> found = true },
    )
    return found
}

/**
 * Waits until the screen reaches a condition instead of sleeping or re-reading the tree from the
 * model's side.
 *
 * Every action tool settles for at most ~1.2s after firing. That is the right answer for a tap
 * that opens a panel, and the wrong answer for a network-backed screen: the model sees the old UI,
 * concludes the tap failed, and retries a click that already worked. The alternative — the model
 * polling read_window_tree itself — costs one model round trip per poll.
 *
 * Conditions are ANDed. [text] + [absent] waits for something to go away; [package_name] waits for
 * a foreground app; [screen_change] waits for the node tree to differ from the one at call time.
 */
fun waitForTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "wait_for",
    description = """
        Block until the screen reaches a condition, without spending a model turn per poll.
        Conditions are ANDed; give at least one:
        - text + optional exact — wait until a node's text or content_description contains it
        - text + absent=true — wait until nothing on screen says it (a spinner, "Loading…")
        - package_name — wait until that app is in the foreground
        - screen_change=true — wait until the node tree differs from what it was when you called
        Use it after a tap that triggers a network call, or before reading a screen you expect to
        have moved. Returns matched, elapsed_ms and the resulting screen_state. On timeout you get
        matched=false with reason=timeout — read the tree and decide, do not retry the same wait
        blindly. timeout_ms defaults to 5000, capped at 15000.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text or content description to wait for (or to disappear with absent=true)")
                })
                put("absent", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Invert the text condition: wait until it is gone (default false)")
                })
                put("exact", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Match the text exactly instead of as a case-insensitive substring (default false)")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Wait until this package owns the active window")
                })
                put("screen_change", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Wait until the accessibility tree differs from the one at call time")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Total wait budget (default 5000, max 15000)")
                })
            },
        )
    },
    execute = { input ->
        val text = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        val absent = input.jsonObject["absent"]?.jsonPrimitive?.booleanOrNull ?: false
        val exact = input.jsonObject["exact"]?.jsonPrimitive?.booleanOrNull ?: false
        val packageName = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
        val screenChange = input.jsonObject["screen_change"]?.jsonPrimitive?.booleanOrNull ?: false
        val timeoutMs = (input.jsonObject["timeout_ms"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS)
            .coerceIn(100L, MAX_TIMEOUT_MS)

        if (text.isNullOrEmpty() && packageName.isNullOrEmpty() && !screenChange) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "give at least one condition: text, package_name or screen_change")
                    }.toString()
                )
            )
        }

        val payload = AccessibilityServiceHandle.withService { svc ->
            val start: Long = SystemClock.uptimeMillis()
            // Snapshot taken before the first poll so a change that lands mid-wait is not missed.
            val startHash = if (screenChange) treeSnapshotCached(svc, force = true)?.hash else null
            val startEventUptime = svc.lastWindowEventUptime

            var matched = false
            var timedOut = false
            // Give the UI one poll interval to react before concluding that an absence is real:
            // "wait until Loading… is gone" would otherwise match instantly on a screen that has
            // not started loading yet.
            delay(POLL_INTERVAL_MS)
            while (true) {
                matched = run {
                    val root = try { svc.rootInActiveWindow } catch (t: Throwable) { null }
                        ?: return@run false
                    if (!packageName.isNullOrEmpty() && root.packageName?.toString() != packageName) {
                        return@run false
                    }
                    if (screenChange) {
                        // The tree cannot move without a window event, so skip the walk entirely
                        // while nothing has fired: a 15s wait on a still screen would otherwise
                        // re-walk the tree 125 times for nothing.
                        if (svc.lastWindowEventUptime <= startEventUptime) return@run false
                        val now = treeSnapshotCached(svc, force = true, root = root)?.hash
                        if (now == null || now == startHash) return@run false
                    }
                    if (text.isNullOrEmpty()) return@run true
                    treeContains(svc, root, text, exact) != absent
                }
                if (matched) break
                val elapsedNow: Long = SystemClock.uptimeMillis() - start
                if (elapsedNow >= timeoutMs) {
                    timedOut = true
                    break
                }
                delay(POLL_INTERVAL_MS)
            }

            val elapsed: Long = SystemClock.uptimeMillis() - start
            svc.appendLog(
                ActionLogEntry(
                    type = "wait_for",
                    paramsSummary = buildString {
                        if (!text.isNullOrEmpty()) append(if (absent) "gone:\"${text.take(30)}\"" else "\"${text.take(30)}\"")
                        if (!packageName.isNullOrEmpty()) append(" pkg=$packageName")
                        if (screenChange) append(" change")
                        append(" -> ").append(if (matched) "ok" else "timeout")
                    },
                    success = matched,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            buildJsonObject {
                put("matched", matched)
                if (timedOut) put("reason", "timeout")
                put("elapsed_ms", elapsed)
                put("after", screenStateJson(svc, screenChanged = null))
            }
        }
        streamer.streamIfHeadless(invocationContext, "WaitFor " + (text ?: packageName ?: "screen_change"))
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
