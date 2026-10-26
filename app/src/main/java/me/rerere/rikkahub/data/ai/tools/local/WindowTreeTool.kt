package me.rerere.rikkahub.data.ai.tools.local

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
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
import me.rerere.rikkahub.service.ActionLogEntry
import me.rerere.rikkahub.service.RikkaAccessibilityService

private const val DEFAULT_MAX_NODES = 300
private const val MAX_NODES_HARD_CEILING = 2000

/** How many vanished-node identities a `diff` read reports before truncating. */
private const val MAX_REMOVED_SIGNATURES = 50

/**
 * One node as a single tab-separated line.
 *
 * The tree used to be a JSON array of objects, and about 60% of its bytes were the same seven
 * key names repeated 300 times — `"content_description":` alone costs 24 bytes per node that has
 * one. A fixed-column line says the same thing in a third of the space, and a list of lines is
 * easier to read than a list of objects anyway.
 *
 * Columns (tab separated, trailing empties dropped):
 *   1  node_id@depth   `12:34@3`; no `@` means depth 0
 *   2  flags           c=clickable l=long-clickable s=scrollable e=editable
 *                      S=selected K=checked f=focused P=password d=disabled
 *   3  bounds          `left,top,right,bottom`
 *   4  class           short name: `android.widget.Button` -> `Button`
 *   5  text            `***` for password fields, never the real value
 *   6  desc            content description
 *   7  hint            hint of an empty input — how a blank EditText says what it wants
 *   8  view_id         resource name
 *
 * [rect] must already hold the node's screen bounds — the caller fetches them once per node so
 * the tree walk and the tree hash share a single `getBoundsInScreen` IPC.
 */
internal fun nodeToLine(
    node: AccessibilityNodeInfo,
    windowId: Int,
    traversalIndex: Int,
    rect: Rect,
    depth: Int,
): String {
    val fields = ArrayList<String>(8)
    // depth rides on the id instead of taking its own column: it is read together with the id
    // nine times out of ten, and the unfiltered depth is what tells "a button in a row" from
    // "a button in a toolbar" once the containers have been filtered out.
    fields.add(
        if (depth > 0) "$windowId:$traversalIndex@$depth" else "$windowId:$traversalIndex"
    )
    fields.add(nodeFlags(node))
    fields.add("${rect.left},${rect.top},${rect.right},${rect.bottom}")
    fields.add(shortClassName(node.className?.toString()))
    fields.add(cell(if (node.isPassword) "***" else node.text?.toString()))
    fields.add(cell(node.contentDescription?.toString()))
    fields.add(cell(node.hintText?.toString()))
    fields.add(cell(node.viewIdResourceName))
    // Trailing empties cost nothing to drop and the common button line loses two tabs.
    while (fields.size > 3 && fields[fields.size - 1].isEmpty()) {
        fields.removeAt(fields.size - 1)
    }
    return fields.joinToString("\t")
}

/** The state letters for one node, in the order documented on [nodeToLine]. */
private fun nodeFlags(node: AccessibilityNodeInfo): String = buildString {
    if (node.isClickable) append('c')
    // Long-clickable on its own is a real signal ("press and hold to delete"); on a clickable
    // node it is noise, since most clickable views are also long-clickable.
    if (node.isLongClickable && !node.isClickable) append('l')
    if (node.isScrollable) append('s')
    if (node.isEditable) append('e')
    if (node.isSelected) append('S')
    if (node.isChecked) append('K')
    if (node.isFocused) append('f')
    if (node.isPassword) append('P')
    if (!node.isEnabled) append('d')
}

/** `android.widget.Button` -> `Button`; a bare name is returned unchanged. */
internal fun shortClassName(raw: String?): String {
    val name = raw?.takeIf { it.isNotEmpty() } ?: return ""
    val cut = name.lastIndexOf('.')
    return if (cut in 0 until name.length - 1) name.substring(cut + 1) else name
}

/** A cell cannot contain a tab or a newline: either would split the record. */
internal fun cell(raw: String?): String {
    val value = raw?.takeIf { it.isNotEmpty() } ?: return ""
    if (value.indexOf('\t') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) return value
    return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}

/**
 * What a node *is*, ignoring where it sits and what state it is in: class, view id, text,
 * content description and hint. This is the handle used to re-attach a node_id after the tree
 * shifted — inserting a row above the target moves every traversal index, but leaves these
 * intact. The hint is part of it because two empty inputs are otherwise identical.
 *
 * Password text is masked: this string is compared, stored and (for removed nodes) printed, and
 * the a11y layer happily hands out what the user typed into a password field.
 */
internal fun nodeIdentity(node: AccessibilityNodeInfo): String {
    val text = if (node.isPassword) "***" else node.text?.toString()
    return "${node.className}|${node.viewIdResourceName}|$text|" +
        "${node.contentDescription}|${node.hintText}"
}

/**
 * Stable identity for a node across two reads: the same structural parts the screen-state hash
 * uses (class / text / content description / selection state / screen bounds). Two nodes with
 * the same signature are indistinguishable to the delta, which is exactly the granularity we
 * want. [rect] must already hold the node's screen bounds.
 *
 * `isSelected` / `isChecked` are in the signature because they are the one state change that
 * leaves text, structure and bounds identical: ticking a checkbox, selecting a tab, marking a
 * row. Without them such a click looks like a no-op and the action envelope retries it.
 *
 * Password text is masked here too — a signature is not only hashed, it is also printed in the
 * `removed` list of a diff.
 */
internal fun nodeSignature(node: AccessibilityNodeInfo, rect: Rect): String {
    val text = if (node.isPassword) "***" else node.text?.toString()
    return "${node.className}|$text|${node.contentDescription}|" +
        "${node.isSelected}|${node.isChecked}|" +
        "${rect.left},${rect.top},${rect.right},${rect.bottom}"
}

internal fun defaultFilter(n: AccessibilityNodeInfo, depth: Int): Boolean {
    if (!n.isVisibleToUser) return false
    if (n.isClickable || n.isScrollable || n.isEditable) return true
    val text = n.text?.toString().orEmpty()
    val cd = n.contentDescription?.toString().orEmpty()
    // An empty input has neither text nor a description; its hint is the only thing that says
    // what it is for, and dropping it leaves the model staring at a blank EditText.
    return text.isNotEmpty() || cd.isNotEmpty() || !n.hintText.isNullOrEmpty()
}

/**
 * Finds the root [AccessibilityNodeInfo] of the topmost application window on the given
 * [displayId]. Uses [AccessibilityService.getWindows] (requires
 * `flagRetrieveInteractiveWindows`) and filters by [AccessibilityWindowInfo.getDisplayId]
 * (API 33+). Returns null when no window is found on that display.
 */
internal fun findRootOnDisplay(
    svc: RikkaAccessibilityService,
    displayId: Int,
): AccessibilityNodeInfo? {
    val windows = try {
        svc.windows
    } catch (t: Throwable) {
        null
    } ?: return null
    // Pick the topmost application-type window on the target display.
    // Application windows have type 1 (TYPE_APPLICATION); system windows (status bar,
    // IME) are skipped so the model sees the app content.
    return windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .firstOrNull { it.getDisplayIdCompat() == displayId }
        ?.root
}

/** API-safe wrapper for AccessibilityWindowInfo.getDisplayId (added in API 33). */
private fun android.view.accessibility.AccessibilityWindowInfo.getDisplayIdCompat(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getDisplayId() else 0

fun readWindowTreeTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "read_window_tree",
    description = "Snapshot of the active window's a11y node tree. Default filters to visible nodes that are clickable / scrollable / editable / have text, content_description or a hint. verbose=true skips the filter (use sparingly). max_nodes caps result (default 300, max 2000). package_name errors when the foreground app does not match. display_id reads a virtual display (API 33+; pass the id from virtual_display_create). `nodes` is a list of records whose fields are separated by a TAB, in this order: node_id@depth, flags, bounds, class, text, desc, hint, view_id. flags letters: c=clickable l=long-clickable s=scrollable e=editable S=selected K=checked f=focused P=password d=disabled; password text is always ***. depth is the node's depth in the full tree, so a jump means containers were filtered out — use it to tell which row, card or group a control belongs to. The first field is the node_id for click_node / set_text; it survives a tree shift, and the result reports healed_from when the id had moved. The result also carries screen_state (package, shade_open, ime_visible, display size) and tree_hash. Re-reading an unmoved screen answers {unchanged:true} instead of the whole tree — pass full=true to force it. diff=true returns only the nodes that appeared / disappeared since your previous read.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("verbose", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, return all nodes (default false applies the filter)")
                })
                put("max_nodes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Cap on returned nodes (default 300, max 2000)")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "If set, return wrong_foreground_app error if the foreground app does not match")
                })
                put("full", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Always return the whole tree, even when it is unchanged since your last read (default false).")
                })
                put("diff", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Return only the nodes that appeared / disappeared since your previous read on this conversation (default false). Falls back to the full tree when there is no previous read.")
                })
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Display id to read (default 0 = main display). Pass a virtual display id from virtual_display_create to read its window tree (requires API 33+).")
                })
            }
        )
    },
    execute = { input ->
        me.rerere.rikkahub.service.RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        val verbose = input.jsonObject["verbose"]?.jsonPrimitive?.booleanOrNull ?: false
        val maxNodesRaw = input.jsonObject["max_nodes"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_NODES
        val maxNodes = maxNodesRaw.coerceIn(1, MAX_NODES_HARD_CEILING)
        val pkgFilter = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
        val full = input.jsonObject["full"]?.jsonPrimitive?.booleanOrNull ?: false
        val diff = input.jsonObject["diff"]?.jsonPrimitive?.booleanOrNull ?: false
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.intOrNull ?: 0

        val payload = AccessibilityServiceHandle.withService { svc ->
            // For non-default displays, find windows on that display via getWindows().
            // rootInActiveWindow only returns the default display's active window.
            val root = if (displayId != 0) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    return@withService buildJsonObject {
                        put("error", "display_filter_unsupported")
                        put("reason", "Reading window trees from non-default displays requires API 33+ (Android 13).")
                        put("nodes", buildJsonArray { })
                    }
                }
                findRootOnDisplay(svc, displayId) ?: run {
                    svc.appendLog(
                        ActionLogEntry(
                            type = "read_window_tree",
                            paramsSummary = "no_window_on_display:$displayId",
                            success = false,
                            timestampMs = System.currentTimeMillis(),
                        ),
                    )
                    return@withService buildJsonObject {
                        put("error", "no_window_on_display")
                        put("display_id", displayId)
                        put("detail", "No accessibility window found on this display. The target app may not have drawn yet, or the accessibility API may not expose windows from app-created virtual displays on this device. Try take_screenshot(display_id=$displayId) for visual inspection instead.")
                        put("nodes", buildJsonArray { })
                    }
                }
            } else {
                svc.rootInActiveWindow
            }
            if (root == null) {
                svc.appendLog(
                    ActionLogEntry(
                        type = "read_window_tree",
                        paramsSummary = "no_active_window",
                        success = false,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                return@withService buildJsonObject {
                    put("error", "no_active_window")
                    put("nodes", buildJsonArray { })
                }
            }
            val pkg = root.packageName?.toString().orEmpty()
            if (pkgFilter != null && pkgFilter != pkg) {
                svc.appendLog(
                    ActionLogEntry(
                        type = "read_window_tree",
                        paramsSummary = "wrong_pkg current=$pkg",
                        success = false,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                return@withService buildJsonObject {
                    put("error", "wrong_foreground_app")
                    put("current", pkg)
                    put("nodes", buildJsonArray { })
                }
            }
            val nodes = mutableListOf<String>()
            val signatures = mutableListOf<String>()
            // Remember what each emitted node *was*, so click_node can re-attach its id after the
            // tree shifted instead of asking for a fresh read.
            val identities = HashMap<String, String>()
            val rect = Rect()
            val (emitted, seen, truncated) = svc.traverseTree(
                root = root,
                filter = if (verbose) ({ _, _ -> true }) else (::defaultFilter),
                cap = maxNodes,
                emit = { n, depth, idx ->
                    n.getBoundsInScreen(rect)
                    nodes.add(nodeToLine(n, root.windowId, idx, rect, depth))
                    signatures.add(nodeSignature(n, rect))
                    identities["${root.windowId}:$idx"] = nodeIdentity(n)
                }
            )
            svc.appendLog(
                ActionLogEntry(
                    type = "read_window_tree",
                    paramsSummary = "$emitted/$seen nodes, pkg=$pkg" + if (verbose) ", verbose" else "",
                    success = true,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            val treeHash = fnv1a64(signatures)
            val conversationKey = invocationContext.callerConversationId
            val previous = TreeSnapshotCache.get(conversationKey)
            TreeSnapshotCache.put(conversationKey, treeHash, signatures, identities)

            val header = buildJsonObject {
                put("package", pkg)
                put("window_title", root.window?.title?.toString().orEmpty())
                put("tree_hash", treeHash)
                put("truncated", truncated)
                put("total_seen", seen)
            }

            val tree = when {
                // Nothing moved since this conversation's last read: the model already holds
                // these nodes, so answer in one line instead of paying for the whole tree again.
                !full && !diff && previous != null && previous.hash == treeHash ->
                    JsonObject(
                        header + buildJsonObject {
                            put("unchanged", true)
                            put("nodes", buildJsonArray { })
                            put(
                                "note",
                                "The tree is identical to your previous read_window_tree — reuse the " +
                                    "nodes you already have. Pass full=true if you really need them again.",
                            )
                            put("screen_state", screenStateJson(svc, screenChanged = false, root = root))
                        }
                    )

                // Explicit delta: only what appeared / disappeared since that read.
                diff && previous != null -> run {
                    val previousSignatures = previous.signatures.toSet()
                    val currentSignatures = signatures.toSet()
                    val added = TreeDelta.addedIndices(signatures, previousSignatures)
                    JsonObject(
                        header + buildJsonObject {
                            put("nodes", buildJsonArray {
                                added.forEach { index -> add(nodes[index]) }
                            })
                            put("removed", buildJsonArray {
                                TreeDelta.removedSignatures(currentSignatures, previous.signatures)
                                    .take(MAX_REMOVED_SIGNATURES)
                                    .forEach { add(it) }
                            })
                            put(
                                "note",
                                "`nodes` lists only what appeared since your previous read; `removed` " +
                                    "lists identities that disappeared. node_ids are current — click them " +
                                    "directly. Pass full=true for the whole tree.",
                            )
                            put("screen_state", screenStateJson(svc, screenChanged = null, root = root))
                        }
                    )
                }

                else -> JsonObject(
                    header + buildJsonObject {
                        put("nodes", buildJsonArray { nodes.forEach { add(it) } })
                        put("screen_state", screenStateJson(svc, screenChanged = null, root = root))
                    }
                )
            }
            // Screen-automation experience memory: inline this app's stored playbook the first
            // time it is read this turn, so the agent reuses what worked last time instead of
            // re-deriving it. Surfaced once per app per turn (AgentTurnTracker) and a no-op when
            // cold memory is not configured.
            val playbook = surfaceAppPlaybook(pkg, invocationContext)
            if (playbook is AppPlaybookSurface.Stored) {
                JsonObject(tree + buildJsonObject { putAppPlaybook(playbook) })
            } else {
                tree
            }
        }
        streamer.streamIfHeadless(invocationContext, "ReadWindowTree")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
