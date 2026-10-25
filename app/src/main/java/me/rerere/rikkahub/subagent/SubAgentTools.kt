package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agentdef.AgentDefinition
import me.rerere.rikkahub.data.agentdef.AgentDefinitionResolver

internal fun errEnv(error: String, detail: String): List<UIMessagePart> {
    val obj = buildJsonObject {
        put("error", error)
        put("detail", detail)
    }
    return listOf(UIMessagePart.Text(obj.toString()))
}

/**
 * Phase 11 — sub-agent dispatch + observation tools. They register only when the
 * assistant has the `Sub-agents` Local Tools toggle on, AND the calling conversation is
 * NOT itself headless (the engine refuses recursive dispatch — these tools are not
 * useful inside a sub-agent run).
 *
 * P2-06b — the `agent` argument now names an [AgentDefinition] in the expert library rather
 * than a DataStore-backed `SubAgentProfile`. The list is passed in fresh at tool-construction
 * time (the same way the profile list used to be), so the description below and whether `agent`
 * is offered at all always reflect what is configured right now.
 */

fun subagentDispatchTool(
    engine: SubAgentEngine,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
    definitions: List<AgentDefinition> = emptyList(),
): Tool {
    val enabledDefinitions = AgentDefinitionResolver.enabledDefinitions(definitions)
    // The posture the engine will clamp this dispatch with, read at construction time — the same
    // live value LocalTools consults to decide whether to register this tool at all. Its tone
    // line is appended to the description below: until now `DescriptionTone` was computed on
    // every caps() call and read by nobody, so all five postures shipped identical text.
    val profile = SubAgentDispatchProfileRules.getLiveDispatchProfile()
    val description = buildString {
        append(
            """
                Dispatch a focused sub-agent — a clean-context LLM run that returns a concise summary.

                WHEN TO DISPATCH — two reasons, and only two:
                - the intermediate work would pollute your context (wide exploration, bulk lookup,
                  multi-file audit), or
                - the work splits into parts that are genuinely independent.
                Do NOT dispatch a single small edit, or anything that needs your own judgement,
                iteration, or back-and-forth. A dispatch costs an approval prompt and roughly 20k
                tokens of overhead — for one step it is cheaper to do the work yourself.

                WRITE THE TASK SO IT CAN SUCCEED — the sub-agent does NOT see your conversation:
                - Restate every fact it needs, and INLINE the things it would otherwise go looking
                  for: paths, API signatures, class names, templates, icon lists. Never tell it to
                  "read file X for reference" — an exploring sub-agent burns its whole trip budget
                  on reading (observed: 28 trips / 1.2M input tokens, zero output). For code work,
                  keep a dispatch to at most 3 files.
                - State the acceptance criteria and the exact output shape you want.
                - It must END on a plain-text summary: the dispatcher harvests only its last text
                  reply, so a run that stops on a tool call returns nothing.
                - Pass a short label so the user can recognise the running sub-agent.

                NESTING — a sub-agent may dispatch its own children while its depth is under the
                posture cap (ASSIST 1 / NORMAL 2 / BURST 3 / UNLEASHED 4; depth 0 is the user's own
                conversation). If a dispatch comes back with `recursion_depth`, finish that level
                inline instead of trying to work around it.

                BUDGETS — size them to the task:
                - timeout_seconds: default ${SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS}s foreground,
                  ${SubAgentDefaults.DEFAULT_BACKGROUND_TIMEOUT_SECONDS}s when run_in_background=true;
                  max ${SubAgentDefaults.MAX_TIMEOUT_SECONDS}. Coding or multi-file work needs >= 600.
                - max_trips: default ${SubAgentDefaults.DEFAULT_MAX_TRIPS}, max ${SubAgentDefaults.MAX_MAX_TRIPS}. A lookup needs
                  2-3; a single file 8-12; a multi-file implementation or a full audit needs 20+.
                - reasoning_level: omit to inherit. A lookup rarely needs more than low.
                - run_in_background=true for anything likely to exceed a few minutes: the dispatch
                  returns immediately and the finished run notifies you inside this conversation's
                  next model request. Foreground (default) blocks this tool call until terminal.
                - no_result=true for fire-and-forget work whose output you do not need.

                Concurrency caps: each assistant has its own (default 3, configurable
                ${SubAgentDefaults.MIN_PER_ASSISTANT_CAP}-${SubAgentDefaults.MAX_PER_ASSISTANT_CAP}) and there is a
                global cap across all assistants. An over-cap dispatch waits for a free slot for up to
                ${SubAgentDefaults.SLOT_WAIT_TIMEOUT_MS / 1000}s and only fails if the wait expires, so a burst of
                parallel dispatches no longer loses the runs past the cap — but keep batches sensibly sized.

                Approval-required: every dispatch needs explicit confirmation. Eligible for
                Always Allow if the user trusts the assistant to delegate freely.
            """.trimIndent()
        )
        appendLine()
        appendLine()
        append(
            "To let the sub-agent hear/see what the user just sent, set attach_media=true: the " +
                "newest user message's audio/video attachments are copied into the sub-agent's " +
                "first message. Images are never carried."
        )
        // Wires `DescriptionTone` to the text the model actually reads. The posture has to land
        // in the description — clamping caps is invisible to the model, which is why the tone
        // used to make no difference at all.
        SubAgentDispatchProfileRules
            .descriptionToneNote(SubAgentDispatchProfileRules.caps(profile).descriptionTone)
            ?.let {
                appendLine()
                appendLine()
                append(it)
            }
        if (enabledDefinitions.isNotEmpty()) {
            appendLine()
            appendLine()
            append("Named experts (pass the name as `agent`):\n")
            append(enabledDefinitions.joinToString("\n") { "- ${it.name}: ${it.description}" })
        }
    }
    return Tool(
        name = "subagent_dispatch",
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task", buildJsonObject { put("type", "string") })
                    put("label", buildJsonObject { put("type", "string") })
                    if (enabledDefinitions.isNotEmpty()) {
                        put("agent", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Name of a configured expert (case-insensitive), " +
                                    "supplying that expert's model and system prompt. Unknown " +
                                    "names fail the dispatch and the error lists the valid " +
                                    "names. model_id, if also given, wins over the expert's " +
                                    "model.",
                            )
                        })
                    }
                    put("model_id", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Model for this sub-agent: a model uuid, a provider model id, or a " +
                                "display name (case-insensitive exact match). Ambiguous or unknown " +
                                "values fail the dispatch and the error lists the valid options. " +
                                "Takes precedence over agent's model. Omit to inherit the " +
                                "expert's model (if agent is set), then the parent assistant's " +
                                "sub-agent default, then the conversation's model.",
                        )
                    })
                    put("system_prompt", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Per-run system-prompt layer, appended after the assistant's " +
                                "sub-agent default and the expert's (if `agent` is set). Use it " +
                                "for run-specific rules (output shape, constraints), not for " +
                                "restating the task.",
                        )
                    })
                    put("tools", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        // T-09 / (8) - described only when the assistant has the sub-agent
                        // tool-surface freeze on. With the freeze off this object stays
                        // byte-identical to the pre-T-09 one (prompt-cache safe) and the
                        // parameter is ignored exactly as it always was.
                        if (callerContext.subAgentToolSurfaceEnabled) {
                            put(
                                "description",
                                "T-09: optional allow-list of tool names for the sub-agent. " +
                                    "Omit it (or pass an empty array) to let the sub-agent " +
                                    "inherit its default headless surface. Names this " +
                                    "assistant cannot hand to a headless run - device-UI " +
                                    "tools, per-call-approval tools, and the subagent_* " +
                                    "tools - are rejected outright; names that simply do " +
                                    "not exist are ignored.",
                            )
                        }
                    })
                    // D3 — described unconditionally (the capability is not behind a feature
                    // flag, unlike T-04's include_recent_turns). Meaningful only for a caller
                    // that just received an audio/video attachment.
                    put("attach_media", buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "D3: also send the audio/video attachments of the newest user " +
                                "message in THIS conversation to the sub-agent, so an omni-capable " +
                                "sub-agent can hear/see what the user just sent. Images are never " +
                                "carried. Omit for the default (task text only).",
                        )
                    })
                    put("run_in_background", buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "When true the dispatch returns immediately and the run executes in " +
                                "the background; its completion reaches you as a system " +
                                "notification injected into this conversation's next model " +
                                "request (never a visible chat message, no idle wait). When " +
                                "false (default) this tool call blocks until the run is terminal.",
                        )
                    })
                    put("reasoning_level", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Thinking depth for this sub-agent: one of " +
                                SubAgentDefaults.VALID_REASONING_LEVELS.joinToString(", ") +
                                ". Omit to inherit the assistant's own reasoning level. Size it " +
                                "to the task: a lookup rarely needs more than low/off, deep " +
                                "research may warrant high. Ignored when the run's model does " +
                                "not support reasoning.",
                        )
                        put(
                            "enum",
                            buildJsonArray {
                                SubAgentDefaults.VALID_REASONING_LEVELS.forEach { add(it) }
                            },
                        )
                    })
                    put("no_result", buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "When true the sub-agent's final output is not returned to you " +
                                "(only status, ids and counters), and a background run that " +
                                "succeeds will NOT wake this conversation with a completion " +
                                "notice. Use for fire-and-forget work whose result you do not " +
                                "need, to keep your context small. Failures and timeouts still " +
                                "notify, so silent work never fails in silence.",
                        )
                    })
                    put("timeout_seconds", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Wall-clock cap for this run, in seconds. Default " +
                                "${SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS} for a foreground " +
                                "dispatch and ${SubAgentDefaults.DEFAULT_BACKGROUND_TIMEOUT_SECONDS} when " +
                                "run_in_background=true; max ${SubAgentDefaults.MAX_TIMEOUT_SECONDS}. A " +
                                "coding or multi-file task needs >= 600: the 180s default is the " +
                                "single most common reason a sub-agent dies with nothing to show.",
                        )
                    })
                    put("max_trips", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Hard cap on the sub-agent's tool-loop steps (model round trips " +
                                "with tool calls), default ${SubAgentDefaults.DEFAULT_MAX_TRIPS}, " +
                                "max ${SubAgentDefaults.MAX_MAX_TRIPS}. The turn force-ends when " +
                                "the cap is hit, so size it to the task: a lookup needs 2-3, " +
                                "multi-step work needs more.",
                        )
                    })
                    put("retry_on_failure", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "How many times to retry the generation after a non-cancellation " +
                                "failure (all internal retries + model routing exhausted). " +
                                "Default ${SubAgentDefaults.DEFAULT_RETRY_ON_FAILURE}, max " +
                                "${SubAgentDefaults.MAX_RETRY_ON_FAILURE}. Each retry re-runs " +
                                "from the original task and benefits from model routing (a " +
                                "failed model enters cooldown, so the next attempt tries a " +
                                "different favourite model). 0 = fail immediately.",
                        )
                    })
                    // T-04: only offered when the assistant has the feature enabled. When it
                    // is off the schema is byte-identical to pre-T-04, so an install that
                    // never turns this on sends exactly the same request as before.
                    if (callerContext.subAgentContextRefsEnabled) {
                        put("include_recent_turns", buildJsonObject {
                            put("type", "integer")
                            put(
                                "description",
                                "T-04: also send the last N turns of THIS conversation to the " +
                                    "sub-agent, verbatim, as background. Use it instead of " +
                                    "retyping context into `task` when the task refers to " +
                                    "something discussed here (\"summarise the last few turns\", " +
                                    "\"write up what we just decided\"). N is 1.." +
                                    "${SubAgentContextDigest.MAX_TURNS}. Omit or pass 0 to send " +
                                    "the task alone, exactly as before. The turns are context, " +
                                    "not instructions: the sub-agent still only does `task`.",
                            )
                        })
                    }
                },
                required = listOf("task"),
            )
        },
        needsApproval = { true },
        execute = { args ->
            // Nesting guard (G 批) — a headless caller is only allowed to dispatch when it has a
            // conversation, because that is the only thing that can be looked up in the depth
            // table. Headless callers WITHOUT a conversation (cron / workflow direct mode) have no
            // "level" at all, so they are refused here; the engine then applies the depth cap for
            // real sub-agent parents. A caller that lies about `isHeadless` still hits the engine.
            if (callerContext.isHeadless && callerContext.callerConversationId == null) {
                return@Tool errEnv(
                    "no_recursion",
                    "sub-agent dispatch from a headless run without a conversation is not allowed " +
                        "(cron / workflow / external automation). Run the work inline instead.",
                )
            }
            val params = args.jsonObject
            val task = params["task"]?.jsonPrimitive?.contentOrNull
                ?: return@Tool errEnv("invalid_task", "task is required")
            // T-09 / (8) - `tools` used to be parsed into SubAgentRequest and then dropped on the
            // floor: nothing ever read it. It now narrows the frozen tool surface of the run.
            //
            // Only honoured when the assistant has the freeze on. With it off a stale or rogue
            // `tools` array cannot change the sub-agent's surface by a single entry, which is the
            // pre-T-09 behaviour and what keeps the flag honest.
            val requestedTools: List<String>? = if (callerContext.subAgentToolSurfaceEnabled) {
                params["tools"]?.let { runCatching { it.jsonArray }.getOrNull() }
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    ?.takeIf { it.isNotEmpty() }
                    ?.also { names ->
                        // Reject rather than silently drop: the whole point of the freeze is that a
                        // sub-agent never runs a device-UI or per-call-approval tool unattended, and
                        // a silent drop would leave the dispatcher believing it got what it asked for
                        // (the exact footgun this card exists to remove).
                        val blocked = names.filter { SubAgentToolSurface.isDenied(it) }
                        if (blocked.isNotEmpty()) {
                            return@Tool errEnv(
                                "tool_unavailable_headless",
                                "these tools cannot be handed to a headless sub-agent: " +
                                    blocked.joinToString(", ") { "${it} (${SubAgentToolSurface.denialReason(it)})" } +
                                    ". Drop them from `tools` (or omit `tools`) and retry.",
                            )
                        }
                    }
            } else {
                null
            }
            // Resolved once: the background default timeout is keyed off it, and evaluating the
            // same expression twice is how the two would eventually drift apart.
            val runInBackground = params["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false
            val request = SubAgentRequest(
                task = task,
                modelId = params["model_id"]?.jsonPrimitive?.contentOrNull,
                agentName = params["agent"]?.jsonPrimitive?.contentOrNull,
                systemPrompt = params["system_prompt"]?.jsonPrimitive?.contentOrNull,
                tools = requestedTools,
                runInBackground = runInBackground,
                noResult = params["no_result"]?.jsonPrimitive?.booleanOrNull ?: false,
                // Normalised to lowercase here; the engine's validator rejects unknown values
                // outright, so a typo can never silently fall back to the assistant default.
                reasoningLevel = params["reasoning_level"]?.jsonPrimitive?.contentOrNull
                    ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                // D3 — opt-in, mirroring the schema above; absent means "task text only".
                attachParentMedia = params["attach_media"]?.jsonPrimitive?.booleanOrNull ?: false,
                timeoutSeconds = SubAgentDefaults.resolveTimeoutSeconds(
                    explicit = params["timeout_seconds"]?.jsonPrimitive?.intOrNull,
                    runInBackground = runInBackground,
                ),
                maxTrips = params["max_trips"]?.jsonPrimitive?.intOrNull
                    ?: SubAgentDefaults.DEFAULT_MAX_TRIPS,
                retryOnFailure = params["retry_on_failure"]?.jsonPrimitive?.intOrNull
                    ?: SubAgentDefaults.DEFAULT_RETRY_ON_FAILURE,
                label = params["label"]?.jsonPrimitive?.contentOrNull,
                // T-04: read only if the feature is on, so a stale/rogue `include_recent_turns`
                // from a caller who never enabled it cannot smuggle parent history into a
                // sub-agent. `intOrNull` on a non-number yields null -> no refs -> pre-T-04
                // behaviour.
                contextRefs = params["include_recent_turns"]
                    ?.jsonPrimitive?.intOrNull
                    ?.takeIf { callerContext.subAgentContextRefsEnabled && it > 0 }
                    ?.let { SubAgentContextRefs(recentTurns = it) },
            )
            // The engine's recursion guard checks `HeadlessConversations.isHeadless(parentChatId)`
            // — if the calling conversation is itself headless (cron / sub-agent / workflow /
            // external-automation) we refuse the dispatch. ToolInvocationContext propagation
            // (added 2026-05-07 stability pass) gives us the calling conversation id at tool-
            // construction time. Empty fallback is a no-knowledge sentinel — engine treats it
            // as "not in a headless run" which is correct for the legacy registration paths
            // that don't yet wire context (one-off / test).
            val parentAssistantId = callerContext.callerAssistantId.orEmpty()
            val parentChatId: String? = callerContext.callerConversationId
            when (val res = engine.dispatch(parentAssistantId, parentChatId, request)) {
                is SubAgentEngine.DispatchResult.Reject ->
                    return@Tool errEnv(res.error, res.detail)
                is SubAgentEngine.DispatchResult.Ok ->
                    listOf(UIMessagePart.Text(encodeRun(res.run, res.budgetRemaining).toString()))
            }
        },
    )
}

/**
 * P2-06b — one listing tool for both halves of the sub-agent feature: the live RUNS (what this
 * tool has always returned) and the stored EXPERTS (`AgentDefinition`, what used to be listed
 * only as free text in `subagent_dispatch`'s description).
 *
 * `kind` is offered — and honoured — only when at least one expert exists. An install with no
 * experts therefore sends a `subagent_list` schema byte-for-byte identical to the pre-P2-06b
 * one, which is the whole point of the roster decision: no new tool, no schema churn for
 * installs that never touch the feature. When experts do exist, `runs` (the default) is still
 * exactly the old output, `experts` lists the library, and `all` returns both.
 */
fun subagentListTool(
    registry: SubAgentRegistry,
    definitions: List<AgentDefinition> = emptyList(),
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
): Tool {
    // Gate on ALL definitions (not just the enabled ones): an all-disabled library still has
    // something worth showing, and a library that exists must never silently fall back to the
    // pre-P2-06b schema.
    val offersDefinitions = definitions.isNotEmpty()
    return Tool(
        name = "subagent_list",
        description = buildString {
            append(
                """
                    List sub-agent runs visible to this assistant. Set active_only=true to omit
                    terminal runs. Read-only.
                """.trimIndent().replace("\n", " ")
            )
            if (offersDefinitions) {
                append(" ")
                append(
                    "Pass kind=\"experts\" to list the configured experts instead (their names, " +
                        "ids and properties) — read this before calling subagent_update or " +
                        "subagent_delete so you can pass the right expert. kind=\"all\" returns both."
                )
            }
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("active_only", buildJsonObject { put("type", "boolean") })
                    if (offersDefinitions) {
                        put("kind", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Which collection to list: \"runs\" (default, live sub-agent runs), " +
                                    "\"experts\" (the stored expert definitions), or \"all\".",
                            )
                        })
                    }
                },
                required = emptyList(),
            )
        },
        execute = { args ->
            val params = args.jsonObject
            // G 批 — 第一次 list 时把上一个进程留下的 run 读回来（只读一次）。
            registry.ensureHydrated()
            val activeOnly = params["active_only"]?.jsonPrimitive?.booleanOrNull ?: false
            // With no library configured `kind` is not even described, so a stale value is
            // ignored rather than honoured — the same rule T-04/T-09 apply to their gated
            // parameters. Default is "runs", i.e. the pre-P2-06b behaviour.
            val kind = if (offersDefinitions) {
                params["kind"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: KIND_RUNS
            } else {
                KIND_RUNS
            }
            if (kind != KIND_RUNS && kind != KIND_EXPERTS && kind != KIND_ALL) {
                return@Tool errEnv(
                    "invalid_kind",
                    "kind must be one of \"$KIND_RUNS\", \"$KIND_EXPERTS\" or \"$KIND_ALL\"; got \"$kind\"",
                )
            }
            // Only this conversation's own runs. A bare `subagent_list` used to return every run in
            // the process — ids, labels, tasks and results of chats the user never opened, which is
            // both a leak and the way a model discovers ids to cancel or resume.
            val visibleRuns = registry.list(activeOnly)
                .filter { it.isOwnedBy(callerContext.callerConversationId) }
            val runsJson = if (kind != KIND_EXPERTS) encodeRuns(visibleRuns) else null
            val expertsJson = if (kind != KIND_RUNS) encodeDefinitions(definitions) else null
            listOf(UIMessagePart.Text(buildJsonObject {
                if (runsJson != null) put("runs", runsJson)
                if (expertsJson != null) put("experts", expertsJson)
            }.toString()))
        },
    )
}

private const val KIND_RUNS = "runs"
private const val KIND_EXPERTS = "experts"
private const val KIND_ALL = "all"

private fun encodeDefinitions(definitions: List<AgentDefinition>) = buildJsonArray {
    definitions.forEach { add(encodeDefinition(it)) }
}

fun subagentGetTool(
    registry: SubAgentRegistry,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "subagent_get",
    description = "Fetch the full run record for a sub-agent by id. Read-only.".trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        registry.ensureHydrated()
        val run = registry.get(id) ?: SubAgentRunPersistence.load(id)
            ?: return@Tool errEnv("unknown_id", "no sub-agent run with id $id")
        if (!run.isOwnedBy(callerContext.callerConversationId)) {
            return@Tool errEnv("not_your_run", "run $id was not dispatched from this conversation")
        }
        listOf(UIMessagePart.Text(encodeRun(run).toString()))
    },
)

fun subagentCancelTool(
    registry: SubAgentRegistry,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "subagent_cancel",
    description = """
        Cancel a running sub-agent by id. Marks the run CANCELLED; safe to call on
        already-terminal runs (returns ok=false). Read-only from the user's perspective
        — no approval required.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        // Ownership first: the run id is the only thing the caller sends, so without this any
        // conversation could cancel another conversation's run.
        registry.ensureHydrated()
        val run = registry.get(id) ?: SubAgentRunPersistence.load(id)
            ?: return@Tool errEnv("unknown_id", "no sub-agent run with id $id")
        if (!run.isOwnedBy(callerContext.callerConversationId)) {
            return@Tool errEnv("not_your_run", "run $id was not dispatched from this conversation")
        }
        // Cancel and let the run unwind. The status flip deliberately does NOT happen here: a
        // terminal write first made the run look finished while the generation was still alive,
        // and it also short-circuited the completion handler (which skips already-terminal runs)
        // — so nothing persisted the cancellation and the ledger row was never settled. The
        // engine's cancellation path now owns both, inside NonCancellable.
        val cancelled = registry.requestCancel(id)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", cancelled)
            put("id", id)
        }.toString()))
    },
)

// ---------------------------------------------------------------------------------------------
// 长跑能力：续聊（subagent_send）与并行 fan-out（subagent_fanout）
// ---------------------------------------------------------------------------------------------

/**
 * `subagent_send` — 给一个已经结束的子代理再推一句话。
 *
 * 这是 Claude Code 里 `SendMessage(agent_id, ...)` 的对应物：同一个子代理、同一段上下文，接着干，
 * 而不是把上一轮的结论压缩成一段话重新派发。它解决的是「追问」场景 —— 第一轮让它调研，
 * 第二轮让它“只把第 3 点展开”，这时新开一个代理就得重讲一遍背景。
 *
 * 与 dispatch 一样需要审批（它会真的花 token），并且只在非 headless 的父会话里注册。
 */
fun subagentSendTool(
    engine: SubAgentEngine,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "subagent_send",
    description = """
        Continue a finished sub-agent with one more message — same context window, same tool
        allow-list, same expert. Use it for follow-ups ("expand point 3", "now do the same for
        the other file") instead of dispatching a new agent and restating everything.

        Only a terminal run can be continued (SUCCEEDED / FAILED / TIMED_OUT); a run that is
        still going cannot be interleaved, and a CANCELLED run stays cancelled.
        Read-only runs come back as an error envelope — check `subagent_get` first if unsure.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("run_id", buildJsonObject {
                    put("type", "string")
                    put("description", "The run to continue — an id from subagent_dispatch or subagent_list.")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "The follow-up instruction. The sub-agent already knows the previous task and its own findings.")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Wall-clock cap for THIS message. Omit to reuse the original run's timeout.")
                })
                put("run_in_background", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Return immediately and deliver the result as a system notification.")
                })
            },
            required = listOf("run_id", "message"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val params = args.jsonObject
        val runId = params["run_id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return@Tool errEnv("invalid_run_id", "run_id is required")
        val message = params["message"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_message", "message is required")
        val runInBackground = params["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false
        when (
            val res = engine.resume(
                runId = runId,
                message = message,
                parentAssistantId = callerContext.callerAssistantId.orEmpty(),
                parentChatId = callerContext.callerConversationId,
                timeoutSeconds = params["timeout_seconds"]?.jsonPrimitive?.intOrNull,
                runInBackground = runInBackground,
            )
        ) {
            is SubAgentEngine.DispatchResult.Reject -> errEnv(res.error, res.detail)
            is SubAgentEngine.DispatchResult.Ok ->
                listOf(UIMessagePart.Text(encodeRun(res.run, res.budgetRemaining).toString()))
        }
    },
)

/**
 * `subagent_fanout` — 一次调用里并发跑 N 个互相独立的任务。
 *
 * Moxw 的生成循环是**顺序**执行一条消息里的多个工具调用的，所以「一条消息发 4 个 dispatch」
 * 并不会并发；要么用 `run_in_background` 逐个扔出去，要么用这个工具一次扇出。后者把并发放进
 * dispatch 自己手里，父代理只需要等一次、拿回一张汇总表。
 *
 * 上限 [SubAgentFanoutRules.MAX_TASKS]（6 个）：再多收益递减，而且引擎的并发闸门会让它们排队。
 */
fun subagentFanoutTool(
    engine: SubAgentEngine,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
    definitions: List<AgentDefinition> = emptyList(),
): Tool = Tool(
    name = "subagent_fanout",
    description = """
        Dispatch several independent sub-agents in PARALLEL — one call, one wait, all results.

        Use it when the work splits into parts that do not depend on each other (audit N modules,
        check M files, research K questions). Do NOT use it for steps that must happen in order:
        those belong in one dispatch, or in sequential calls.

        Each entry in `tasks` takes the same fields as subagent_dispatch (task, label, agent,
        model_id, max_trips, timeout_seconds, no_result). At most 6 tasks per call; the engine's
        concurrency cap still applies, so extra tasks queue rather than fail.

        With run_in_background=true the call returns handles immediately and results arrive as
        system notifications — use that for work measured in tens of minutes.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("tasks", buildJsonObject {
                    put("type", "array")
                    put("description", "Independent tasks, each {task, label?, agent?, model_id?, max_trips?, timeout_seconds?, no_result?}.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("task", buildJsonObject { put("type", "string") })
                            put("label", buildJsonObject { put("type", "string") })
                            put("agent", buildJsonObject { put("type", "string") })
                            put("model_id", buildJsonObject { put("type", "string") })
                            put("max_trips", buildJsonObject { put("type", "integer") })
                            put("timeout_seconds", buildJsonObject { put("type", "integer") })
                            put("no_result", buildJsonObject { put("type", "boolean") })
                        })
                        put("required", buildJsonArray { add("task") })
                    })
                })
                put("run_in_background", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Return handles immediately instead of waiting for all results.")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Default wall-clock cap for tasks that do not set their own.")
                })
                put("max_trips", buildJsonObject {
                    put("type", "integer")
                    put("description", "Default tool-step cap for tasks that do not set their own.")
                })
            },
            required = listOf("tasks"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val params = args.jsonObject
        val runInBackground = params["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false
        val plan = SubAgentFanoutRules.plan(
            tasks = runCatching { params["tasks"]?.jsonArray }.getOrNull(),
            runInBackground = runInBackground,
            defaultTimeoutSeconds = params["timeout_seconds"]?.jsonPrimitive?.intOrNull,
            defaultMaxTrips = params["max_trips"]?.jsonPrimitive?.intOrNull,
        )
        if (plan is SubAgentFanoutRules.Plan.Invalid) {
            return@Tool errEnv(plan.code, plan.reason)
        }
        val requests = (plan as SubAgentFanoutRules.Plan.Ok).requests
        val parentAssistantId = callerContext.callerAssistantId.orEmpty()
        val parentChatId: String? = callerContext.callerConversationId
        // 真正的并发点：每个任务一条协程，引擎自己的并发闸门负责排队与拒绝。
        // `async` 必须在 coroutineScope 的接收者上调用 —— 全限定写法会丢掉接收者，
        // 编译成 deprecated 的无 scope 版本（CI 第一次就是在这里红的）。
        val results = coroutineScope {
            requests
                .map { request ->
                    async { engine.dispatch(parentAssistantId, parentChatId, request) to request }
                }
                .awaitAll()
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("count", results.size)
            put("runs", buildJsonArray {
                results.forEach { (result, request) ->
                    when (result) {
                        is SubAgentEngine.DispatchResult.Ok ->
                            add(encodeRun(result.run, result.budgetRemaining))
                        is SubAgentEngine.DispatchResult.Reject -> add(buildJsonObject {
                            put("error", result.error)
                            put("detail", result.detail)
                            put("task", request.task.take(120))
                        })
                    }
                }
            })
        }.toString()))
    },
)
