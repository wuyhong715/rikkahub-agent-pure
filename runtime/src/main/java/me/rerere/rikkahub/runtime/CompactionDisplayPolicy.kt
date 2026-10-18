package me.rerere.rikkahub.runtime

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart

/**
 * The contract for the UI-only "context compaction" card (P4).
 *
 * The compaction planner (:runtime) must recognise these synthetic tool calls so it can skip
 * them when it measures or rebuilds the request, but the card itself is built and rendered by
 * :app's ContextCompactionPresentation. Both sides therefore share the two literals and the
 * predicate here, so the name/marker can never drift apart.
 */

/** Synthetic tool name that carries a compaction card in the transcript. */
const val COMPACTION_DISPLAY_TOOL_NAME = "context_compaction"

/** Metadata key whose presence+`true` marks the tool call as display-only. */
const val COMPACTION_DISPLAY_ONLY_KEY = "rikkahub_display_only_context_compaction"

/** True when [part] is the display-only compaction card (never model input). */
fun isCompactionDisplayPart(part: UIMessagePart): Boolean =
    part is UIMessagePart.Tool &&
        part.toolName == COMPACTION_DISPLAY_TOOL_NAME &&
        part.metadata?.get(COMPACTION_DISPLAY_ONLY_KEY)?.jsonPrimitive?.booleanOrNull == true
