package me.rerere.rikkahub.data.ai.tools.local

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
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
import me.rerere.rikkahub.runtime.AgentTurnTracker
import me.rerere.rikkahub.runtime.AutomationRecorder
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.service.ActionLogEntry
import me.rerere.rikkahub.service.RikkaAccessibilityService

private val ALLOWED_BY = setOf("text", "content_description", "view_id_resource_name")
private const val MATCH_CAP = 50

/** How many values a miss scans for suggestions before giving up on completeness. */
private const val SUGGESTION_POOL_CAP = 300

private data class NodeMatch(
    val node: AccessibilityNodeInfo,
    val traversalIndex: Int,
    val depth: Int,
)

/**
 * Matches nodes via the same traversal (and therefore the same traversalIndex numbering)
 * as read_window_tree, so returned node_ids agree across tools and a node visible in the
 * tree can never be no_match. Exact string match per axis. recycle stays OFF because
 * matched nodes are retained for clicking.
 */
private fun findMatchesUnified(
    svc: RikkaAccessibilityService,
    root: AccessibilityNodeInfo,
    by: String,
    value: String,
): Pair<List<NodeMatch>, Boolean> {
    val out = mutableListOf<NodeMatch>()
    val (_, _, truncated) = svc.traverseTree(
        root = root,
        filter = { n, _ ->
            when (by) {
                "text" -> n.text?.toString() == value
                "content_description" -> n.contentDescription?.toString() == value
                "view_id_resource_name" -> n.viewIdResourceName == value
                else -> false
            }
        },
        cap = MATCH_CAP,
        emit = { n, depth, idx -> out.add(NodeMatch(n, idx, depth)) },
    )
    return out to truncated
}

/** Ceiling on the candidates considered when healing a stale node_id. */
private const val HEAL_MATCH_CAP = 200

/** What a node_id resolved to. [healedFrom] is set when the index was stale but the node was found again. */
private data class NodeResolution(
    val node: AccessibilityNodeInfo?,
    val reason: String? = null,
    val healedFrom: String? = null,
)

/**
 * Resolves a read_window_tree node_id back to a live node.
 *
 * A traversal index is not stable: it counts every node in the full walk, so inserting one row
 * above the target — a new message, an ad, the IME pushing the layout up — shifts every index
 * after it. The old behaviour was to answer stale_node_id and make the model re-read the whole
 * tree, which costs a round trip and a full tree's worth of tokens for a screen that did not
 * change at all.
 *
 * So the read that produced the id also remembered what each node *was* ([nodeIdentity]). When
 * the index now holds a different node, or holds nothing, this looks for the remembered identity
 * instead and takes the instance whose index moved least — sibling duplicates keep their order,
 * so nearest wins. [healedFrom] reports the id the model sent, so the tool result can hand back
 * a fresh one.
 */
private fun resolveByNodeId(
    svc: RikkaAccessibilityService,
    root: AccessibilityNodeInfo,
    windowId: Int,
    traversalIndex: Int,
    conversationKey: String?,
): NodeResolution {
    if (root.windowId != windowId) return NodeResolution(null, "stale_node_id")

    var found: AccessibilityNodeInfo? = null
    // filter=true makes emitted == seen, so cap=traversalIndex stops exactly at the node.
    svc.traverseTree(
        root = root,
        filter = { _, _ -> true },
        cap = traversalIndex,
        emit = { n, _, idx -> if (idx == traversalIndex) found = n },
    )
    val direct = found
    val expected = TreeSnapshotCache.get(conversationKey)
        ?.identities
        ?.get("$windowId:$traversalIndex")

    if (direct != null && direct.isVisibleToUser) {
        // No fingerprint on record (the id predates the last read) — trust the index as before.
        if (expected == null) return NodeResolution(direct)
        // The node at that index is still the one the model saw.
        if (nodeIdentity(direct) == expected) return NodeResolution(direct)
    }
    if (expected == null) return NodeResolution(null, "stale_node_id")

    var best: AccessibilityNodeInfo? = null
    var bestDelta: Int = Int.MAX_VALUE
    svc.traverseTree(
        root = root,
        filter = { n, _ -> n.isVisibleToUser && nodeIdentity(n) == expected },
        cap = HEAL_MATCH_CAP,
        emit = { n, _, idx: Int ->
            val delta: Int = if (idx > traversalIndex) idx - traversalIndex else traversalIndex - idx
            if (delta < bestDelta) {
                bestDelta = delta
                best = n
            }
        },
    )
    val healed: AccessibilityNodeInfo = best ?: return NodeResolution(null, "stale_node_id")
    return NodeResolution(healed, healedFrom = "$windowId:$traversalIndex")
}

private fun parseSelector(input: kotlinx.serialization.json.JsonElement): Triple<String?, String?, String?> {
    val by = input.jsonObject["by"]?.jsonPrimitive?.contentOrNull
    val value = input.jsonObject["value"]?.jsonPrimitive?.contentOrNull
    val pkg = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
    return Triple(by, value, pkg)
}

/** The value a node exposes on [by]'s axis, or null when it has none. */
private fun axisValue(node: AccessibilityNodeInfo, by: String): String? = when (by) {
    "text" -> node.text?.toString()?.takeIf { it.isNotBlank() }
    "content_description" -> node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
    "view_id_resource_name" -> node.viewIdResourceName?.takeIf { it.isNotBlank() }
    else -> null
}

/**
 * The values actually on screen for [by]'s axis, each with the traversal index needed to rebuild
 * a node_id. Runs over the same traversal as the match itself, so a suggested node_id is valid.
 */
private fun collectAxisValues(
    svc: RikkaAccessibilityService,
    root: AccessibilityNodeInfo,
    by: String,
): List<Pair<String, Int>> {
    val out = mutableListOf<Pair<String, Int>>()
    svc.traverseTree(
        root = root,
        filter = { n, _ -> axisValue(n, by) != null },
        cap = SUGGESTION_POOL_CAP,
        emit = { n, _, idx -> axisValue(n, by)?.let { out.add(it to idx) } },
    )
    return out
}

/**
 * "Did you mean one of these?" — the closest values on the same axis, as clickable node_ids.
 *
 * Answers the one failure mode that most reliably stalls a run: a selector that misses, with no
 * hint about what *is* there, so the model retries the same wrong value or pays for a fresh
 * `read_window_tree`. Empty when nothing is close enough to be worth suggesting.
 */
private fun suggestAlternativesJson(
    svc: RikkaAccessibilityService,
    root: AccessibilityNodeInfo,
    by: String,
    value: String,
): kotlinx.serialization.json.JsonArray {
    val pool = collectAxisValues(svc, root, by)
    val ranked = NodeDiagnostics.rank(value, pool.map { it.first })
    return buildJsonArray {
        ranked.forEach { candidate ->
            val (candidateValue, traversalIndex) = pool[candidate.index]
            add(buildJsonObject {
                put("node_id", "${root.windowId}:$traversalIndex")
                put("by", by)
                put("value", candidateValue)
                put("score", candidate.score)
            })
        }
    }
}

fun findNodeTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "find_node",
    description = """
        Find accessibility nodes in the active window matching a selector. by: text |
        content_description | view_id_resource_name. Returns {matches: [...]} with at most 50
        node summaries. Use read_window_tree first to see what's available, then this for
        targeted lookups. Matching runs over the same traversal as read_window_tree, so
        returned node_ids agree across both tools; truncated=true means more than 50 nodes
        matched.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("by", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("text"); add("content_description"); add("view_id_resource_name") })
                    put("description", "Selector axis")
                })
                put("value", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact value to match")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional foreground package guard")
                })
            },
            required = listOf("by", "value")
        )
    },
    execute = { input ->
        me.rerere.rikkahub.service.RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        val (by, value, pkgFilter) = parseSelector(input)
        if (by == null || by !in ALLOWED_BY || value == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "by must be one of [text, content_description, view_id_resource_name] and value is required")
                    }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            val root = svc.rootInActiveWindow
            if (root == null) {
                return@withService buildJsonObject {
                    put("error", "no_active_window")
                    put("matches", buildJsonArray {})
                }
            }
            val pkg = root.packageName?.toString().orEmpty()
            if (pkgFilter != null && pkgFilter != pkg) {
                return@withService buildJsonObject {
                    put("error", "wrong_foreground_app")
                    put("current", pkg)
                    put("matches", buildJsonArray {})
                }
            }
            val (matches, truncated) = findMatchesUnified(svc, root, by, value)
            if (matches.isEmpty()) {
                AutomationRecorder.recordAction(
                    packageName = pkg,
                    screen = svc.lastWindowClassName,
                    action = "find",
                    selector = AutomationRecorder.selectorLabel(by, value),
                    ok = false,
                )
            }
            svc.appendLog(
                ActionLogEntry(
                    type = "find_node",
                    paramsSummary = "$by=\"${value.take(40)}\" -> ${matches.size}",
                    success = true,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            // Remember what these nodes were, so a click on one of these ids survives the list
            // re-laying out between find_node and click_node.
            TreeSnapshotCache.remember(
                invocationContext.callerConversationId,
                matches.associate { "${root.windowId}:${it.traversalIndex}" to nodeIdentity(it.node) },
            )
            buildJsonObject {
                put("matches", buildJsonArray {
                    matches.forEach { m ->
                        val rect = Rect()
                        m.node.getBoundsInScreen(rect)
                        add(nodeToJson(m.node, root.windowId, m.traversalIndex, rect, m.depth))
                    }
                })
                if (truncated) put("truncated", true)
                if (matches.isEmpty()) {
                    put("candidates", suggestAlternativesJson(svc, root, by, value))
                    put(
                        "hint",
                        "No exact match on by=$by. `candidates` holds the closest values present " +
                            "on that axis — click one by node_id, or try by=view_id_resource_name.",
                    )
                }
                put("screen_state", screenStateJson(svc, screenChanged = null, root = root))
            }
        }
        streamer.streamIfHeadless(invocationContext, "FindNode $by=\"${value.take(30)}\"")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun clickNodeTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "click_node",
    description = """
        Click a node. Preferred selector: node_id from read_window_tree / find_node. Alternative:
        by (text | content_description | view_id_resource_name) + value with optional nth. If the
        matched node is not clickable, the nearest clickable ancestor is clicked. The click is
        tried as an accessibility action first and retried as a real touch when the view ignores
        it; `resolved_via` says which one landed: action | gesture | gesture_retry. A node_id
        whose index moved (a row was inserted above it, the IME pushed the layout up) is re-found
        by node identity and the click still lands, reported as healed_from; only a node that is
        really gone returns stale_node_id, and only then should you re-run read_window_tree. The
        result carries an "after" object; check screen_changed to confirm the click did something,
        and its new_nodes to see what appeared without paying for another read.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("node_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Node handle from read_window_tree/find_node (\"windowId:index\"). Preferred over by/value; on a stale id you get stale_node_id and should re-read the tree.")
                })
                put("by", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("text"); add("content_description"); add("view_id_resource_name") })
                })
                put("value", buildJsonObject { put("type", "string") })
                put("package_name", buildJsonObject { put("type", "string") })
                put("nth", buildJsonObject {
                    put("type", "integer")
                    put("description", "Zero-based index when multiple nodes match (default 0)")
                })
            },
        )
    },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        val (by, value, pkgFilter) = parseSelector(input)
        val nth = input.jsonObject["nth"]?.jsonPrimitive?.intOrNull ?: 0
        val rawNodeId = input.jsonObject["node_id"]?.jsonPrimitive?.contentOrNull
        val parsedNodeId = rawNodeId?.let { parseNodeId(it) }
        if (rawNodeId != null && parsedNodeId == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "node_id must look like \"windowId:index\" as returned by read_window_tree")
                    }.toString()
                )
            )
        }
        if (rawNodeId == null && (by == null || by !in ALLOWED_BY || value == null)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "provide node_id, or by (one of [text, content_description, view_id_resource_name]) plus value")
                    }.toString()
                )
            )
        }
        if (nth < 0) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "nth must be >= 0")
                    }.toString()
                )
            )
        }
        // Resolved inside the action body, read by the envelope's retry hook: a view that
        // accepts ACTION_CLICK without doing anything can only be retried as a real touch.
        var clickTarget: AccessibilityNodeInfo? = null
        val payload = AccessibilityServiceHandle.withService { svc ->
            withActionEnvelope(
                svc,
                retryWhenUnchanged = { s, actResult ->
                    val targetNode = clickTarget
                    when {
                        targetNode == null -> null
                        actResult["success"]?.jsonPrimitive?.booleanOrNull != true -> null
                        !s.tapNode(targetNode) -> null
                        else -> buildJsonObject {
                            put("resolved_via", "gesture_retry")
                            put("reason", "ACTION_CLICK reported success but the tree did not change")
                        }
                    }
                },
            ) click@{ _ ->
                val root = svc.rootInActiveWindow
                    ?: return@click buildJsonObject { put("error", "no_active_window") }
                val pkg = root.packageName?.toString().orEmpty()
                if (pkgFilter != null && pkgFilter != pkg) {
                    return@click buildJsonObject {
                        put("error", "wrong_foreground_app")
                        put("current", pkg)
                    }
                }
                var resolution: NodeResolution? = null
                val (target, staleReason) = if (parsedNodeId != null) {
                    val resolved = resolveByNodeId(
                        svc = svc,
                        root = root,
                        windowId = parsedNodeId.first,
                        traversalIndex = parsedNodeId.second,
                        conversationKey = invocationContext.callerConversationId,
                    )
                    resolution = resolved
                    resolved.node to resolved.reason
                } else {
                    val (matches, truncated) = findMatchesUnified(svc, root, by!!, value!!)
                    when {
                        matches.isEmpty() -> null to "no_match"
                        nth >= matches.size -> return@click buildJsonObject {
                            put("error", "nth_out_of_range")
                            put("available", matches.size)
                            if (truncated) put("truncated", true)
                        }
                        else -> matches[nth].node to null
                    }
                }
                if (target == null) {
                    AutomationRecorder.recordAction(
                        packageName = pkg,
                        screen = svc.lastWindowClassName,
                        action = "click",
                        selector = AutomationRecorder.selectorLabel(by, value),
                        ok = false,
                    )
                    return@click buildJsonObject {
                        put("error", staleReason ?: "no_match")
                        if (staleReason == "stale_node_id") {
                            put("hint", "the screen changed since read_window_tree; re-run it and use a fresh node_id")
                        }
                        if (staleReason == "no_match" && by != null && value != null) {
                            put("candidates", suggestAlternativesJson(svc, root, by, value))
                            put(
                                "hint",
                                "No exact match on by=$by — the closest values on that axis are in " +
                                    "`candidates`. Click one by node_id, or retry with " +
                                    "by=view_id_resource_name, which survives label changes.",
                            )
                        }
                    }
                }
                val clickable = svc.resolveClickable(target)
                    ?: return@click buildJsonObject { put("error", "no_clickable_ancestor") }
                clickTarget = clickable
                val actionOk = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                // ACTION_CLICK is refused outright by a fair share of Compose, WebView and
                // custom-drawn views. Fall back to a real touch immediately. The slower failure —
                // the view accepts the action and does nothing — is caught by the envelope's
                // retryWhenUnchanged hook once the UI has settled.
                val ok = actionOk || svc.tapNode(clickable)
                val via = when {
                    actionOk -> "action"
                    ok -> "gesture"
                    else -> "none"
                }
                AutomationRecorder.recordAction(
                    packageName = pkg,
                    screen = svc.lastWindowClassName,
                    action = "click",
                    selector = AutomationRecorder.selectorLabel(by, value)
                        ?: AutomationRecorder.nodeLabel(
                            clickable.viewIdResourceName,
                            clickable.text?.toString(),
                            clickable.contentDescription?.toString(),
                        ),
                    ok = ok,
                )
                svc.appendLog(
                    ActionLogEntry(
                        type = "click_node",
                        paramsSummary = (rawNodeId ?: "$by=\"${value?.take(40)}\" nth=$nth") +
                            " -> " + if (ok) via else "fail",
                        success = ok,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                buildJsonObject {
                    put("success", ok)
                    put("resolved_via", via)
                    resolution?.healedFrom?.let { staleId ->
                        put("healed_from", staleId)
                        put(
                            "note",
                            "node_id $staleId was stale: the tree shifted and that index no longer " +
                                "holds this node. It was re-found by identity, so the click landed. " +
                                "Use the node_id from a fresh read from now on.",
                        )
                    }
                    put("clicked", buildJsonObject {
                        val rect = android.graphics.Rect()
                        clickable.getBoundsInScreen(rect)
                        put("bounds", buildJsonArray {
                            add(rect.left); add(rect.top); add(rect.right); add(rect.bottom)
                        })
                        put("text", clickable.text?.toString() ?: "")
                        put("resolved_via_ancestor", clickable !== target)
                    })
                }
            }
        }
        streamer.streamIfHeadless(invocationContext, "ClickNode " + (rawNodeId ?: "$by=\"${value?.take(30)}\""))
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/**
 * Type text into an editable accessibility node by selector. Works on standard input fields
 * (URL bars, search boxes, form fields, IME-driven text views). Does NOT work on terminals
 * like Termux because they render to a Surface and do not expose editable nodes; for Termux
 * use termux_run_command instead.
 */
fun setTextTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "set_text",
    description = """
        Type or replace text in an editable field, selected by node_id (preferred) or by/value.
        Type or replace text in an editable input field on screen. Find the field by selector
        (text / content_description / view_id_resource_name). Works for URL bars, search boxes,
        form fields. Does NOT work for terminals like Termux that render natively - for Termux
        use termux_run_command if that tool is enabled, otherwise report that terminals cannot
        be typed into. Returns {success, set_to, ...} or a structured error. The result carries
        an "after" object; check screen_changed in the after object to confirm the text landed.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("node_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Node handle from read_window_tree/find_node (\"windowId:index\"). Preferred over by/value; on a stale id you get stale_node_id and should re-read the tree.")
                })
                put("by", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("text"); add("content_description"); add("view_id_resource_name") })
                    put("description", "Selector axis to find the editable node")
                })
                put("value", buildJsonObject {
                    put("type", "string")
                    put("description", "Selector value to match against")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The text to set on the matched node")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional foreground package guard")
                })
                put("nth", buildJsonObject {
                    put("type", "integer")
                    put("description", "Zero-based index when multiple nodes match (default 0)")
                })
            },
            required = listOf("text")
        )
    },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        val (by, value, pkgFilter) = parseSelector(input)
        val nth = input.jsonObject["nth"]?.jsonPrimitive?.intOrNull ?: 0
        val newText = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        val rawNodeId = input.jsonObject["node_id"]?.jsonPrimitive?.contentOrNull
        val parsedNodeId = rawNodeId?.let { parseNodeId(it) }
        if (newText == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "text is required") }.toString()
                )
            )
        }
        if (rawNodeId != null && parsedNodeId == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "node_id must look like \"windowId:index\" as returned by read_window_tree")
                    }.toString()
                )
            )
        }
        if (rawNodeId == null && (by == null || by !in ALLOWED_BY || value == null)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "provide node_id, or by (one of [text, content_description, view_id_resource_name]) plus value")
                    }.toString()
                )
            )
        }
        if (nth < 0) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "nth must be >= 0") }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            withActionEnvelope(svc) { _ ->
                val root = svc.rootInActiveWindow
                    ?: return@withActionEnvelope buildJsonObject { put("error", "no_active_window") }
                val pkg = root.packageName?.toString().orEmpty()
                if (pkgFilter != null && pkgFilter != pkg) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", "wrong_foreground_app")
                        put("current", pkg)
                    }
                }
                var resolution: NodeResolution? = null
                val (target, staleReason) = if (parsedNodeId != null) {
                    val resolved = resolveByNodeId(
                        svc = svc,
                        root = root,
                        windowId = parsedNodeId.first,
                        traversalIndex = parsedNodeId.second,
                        conversationKey = invocationContext.callerConversationId,
                    )
                    resolution = resolved
                    resolved.node to resolved.reason
                } else {
                    val (matches, truncated) = findMatchesUnified(svc, root, by!!, value!!)
                    when {
                        matches.isEmpty() -> null to "no_match"
                        nth >= matches.size -> return@withActionEnvelope buildJsonObject {
                            put("error", "nth_out_of_range")
                            put("available", matches.size)
                            if (truncated) put("truncated", true)
                        }
                        else -> matches[nth].node to null
                    }
                }
                if (target == null) {
                    AutomationRecorder.recordAction(
                        packageName = pkg,
                        screen = svc.lastWindowClassName,
                        action = "set_text",
                        selector = AutomationRecorder.selectorLabel(by, value),
                        ok = false,
                    )
                    return@withActionEnvelope buildJsonObject {
                        put("error", staleReason ?: "no_match")
                        if (staleReason == "stale_node_id") {
                            put("hint", "the screen changed since read_window_tree; re-run it and use a fresh node_id")
                        }
                        if (staleReason == "no_match" && by != null && value != null) {
                            put("candidates", suggestAlternativesJson(svc, root, by, value))
                            put(
                                "hint",
                                "No exact match on by=$by — the closest values on that axis are in " +
                                    "`candidates`. Click one by node_id, or retry with " +
                                    "by=view_id_resource_name, which survives label changes.",
                            )
                        }
                    }
                }
                // ACTION_SET_TEXT requires the node to be editable. Walk up the parent chain
                // looking for one - some apps wrap their EditText in a non-editable container.
                var editable: AccessibilityNodeInfo? = target
                while (editable != null && !editable.isEditable) {
                    editable = editable.parent
                }
                if (editable == null) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", "node_not_editable")
                        put("recovery", "The matched node is not an editable input. Terminals (Termux) render natively and do not expose editable nodes; if the termux_run_command tool is enabled use it instead, otherwise report that terminals cannot be typed into.")
                    }
                }
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        newText
                    )
                }
                val ok = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                // Deliberately no typed value: the text the user dictated must not be persisted.
                AutomationRecorder.recordAction(
                    packageName = pkg,
                    screen = svc.lastWindowClassName,
                    action = "set_text",
                    selector = AutomationRecorder.selectorLabel(by, value)
                        ?: AutomationRecorder.nodeLabel(
                            editable.viewIdResourceName,
                            null,
                            editable.contentDescription?.toString(),
                        ),
                    ok = ok,
                )
                svc.appendLog(
                    ActionLogEntry(
                        type = "set_text",
                        paramsSummary = (rawNodeId ?: "$by=\"${value?.take(30)}\"") + " -> \"${newText.take(30)}\"",
                        success = ok,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                buildJsonObject {
                    put("success", ok)
                    if (!ok) put("reason", "action_rejected")
                    put("set_to", newText)
                    resolution?.healedFrom?.let { staleId ->
                        put("healed_from", staleId)
                        put(
                            "note",
                            "node_id $staleId was stale: the tree shifted and that index no longer " +
                                "holds this field. It was re-found by identity, so the text landed. " +
                                "Use the node_id from a fresh read from now on.",
                        )
                    }
                }
            }
        }
        streamer.streamIfHeadless(invocationContext, "SetText " + (rawNodeId ?: "$by=\"${value?.take(20)}\"") + " -> \"${newText.take(20)}\"")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
