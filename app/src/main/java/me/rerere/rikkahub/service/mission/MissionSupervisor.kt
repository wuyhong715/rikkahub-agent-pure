package me.rerere.rikkahub.service.mission

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionExecutionState
import me.rerere.rikkahub.data.mission.MissionHookSpec
import me.rerere.rikkahub.data.mission.MissionHookWhen
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.MissionTaskState
import me.rerere.rikkahub.data.mission.MissionTimeBudget
import me.rerere.rikkahub.data.mission.AcceptanceOutcome
import me.rerere.rikkahub.data.mission.executionStateEnum
import me.rerere.rikkahub.data.mission.isLive
import me.rerere.rikkahub.data.mission.acceptanceCriteria
import me.rerere.rikkahub.data.mission.isRecurring
import me.rerere.rikkahub.data.mission.limits
import me.rerere.rikkahub.data.mission.statusEnum
import me.rerere.rikkahub.data.mission.toArtifact
import me.rerere.rikkahub.data.mission.workspaceIdOrNull
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.runtime.util.JsonInstant

/** Exact NOTE title the mission_report persistence writes for `done` (see MissionReportTools). */
private const val SIGNAL_DONE_TITLE: String = "mission_report: done"

/** Exact NOTE title the mission_report persistence writes for `blocked` (see MissionReportTools). */
private const val SIGNAL_BLOCKED_TITLE: String = "mission_report: blocked"

/**
 * NOTE title the mission detail page journals when the user presses "stop" (see
 * MissionDetailVM.stopRun). The round still ends as a normal COMPLETED run — without
 * this check the supervisor would happily "continue" what the user just halted.
 */
private const val USER_STOP_TITLE: String = "user_stop"

// ---- 批 F — 检查命令 / 状态文件的 journal 标题 -------------------------------------------
/** 完成前检查失败（用于计数返工次数）。 */
private const val CHECK_FAILED_TITLE: String = "check_failed"

/** 完成前检查通过的 journal 标题（用于把「连续失败」截断）。 */
private const val CHECK_PASSED_TITLE: String = "check_passed"

/** 轮末检查失败的 journal 标题。 */
private const val ROUND_CHECK_FAILED_TITLE: String = "round_check_failed"

/** 失败后检查失败的 journal 标题。 */
private const val FAILURE_CHECK_FAILED_TITLE: String = "failure_check_failed"

/** 本轮结束时 task-state.md 没有变化的标记。 */
private const val STATE_UNCHANGED_TITLE: String = "state_unchanged"

/** 批 H — 结构化验收逐条结果的 journal 标题。 */
private const val ACCEPTANCE_TITLE: String = "acceptance"

/** 批 H — 到截止时间、任务被 park 给用户的 journal 标题。 */
private const val DEADLINE_TITLE: String = "deadline"

/** How many recent journal entries [MissionSupervisor.start] subscribes to. */
private const val OBSERVE_RECENT_LIMIT: Int = 200

// AgentRunStatus names — the runStatusProbe returns `status.name`.
private const val RUN_STATUS_QUEUED: String = "queued"
private const val RUN_STATUS_AWAITING_APPROVAL: String = "awaiting_approval"
private const val RUN_STATUS_RUNNING: String = "running"
private const val RUN_STATUS_CANCELLED: String = "cancelled"
private const val RUN_STATUS_SUCCEEDED: String = "succeeded"
private const val RUN_STATUS_FAILED: String = "failed"
private const val RUN_STATUS_PROCESS_LOST: String = "process_lost"

/**
 * 批 A · Mission Runtime (B3) — the mission supervisor (兜底督导).
 *
 * The supervisor is the fallback layer that keeps a mission moving when a round stops
 * before its model declared done/blocked. Two feeds, both funnelling into the same
 * evaluate-and-act core:
 *
 * - [start] subscribes to the recent tail of the run-event journal
 *   ([RunEventRepository.observeRecent]) and reacts to `RUN_FINISHED` events of
 *   mission-bound runs (one evaluation per run id);
 * - [onBoot] scans mission rows once per process for a restart orphan: status `ACTIVE`
 *   with execution posture `RUNNING` whose latest run already reached a terminal state
 *   (the run-finished event may never have been emitted if the process died mid-run).
 *
 * Acting on a finished round means: collect the run's facts (done / blocked signals,
 * whether anything progressed, consecutive stall rounds, user intervention), ask
 * [MissionContinuePolicy] for a verdict, and execute it — complete + notify, block +
 * notify, auto-continue via [MissionLauncher], or yield to the user.
 *
 * Invariants (batch A):
 * - **Never throws.** Every public entry point swallows failures via `runCatching`;
 *   journal, probe and notification hiccups degrade to a no-op.
 * - In-memory state only: [processedRuns] and [stallCounts] reset with the process.
 *   The boot scan is the recovery path and is expected to run once per process — the
 *   dedup set covers replays within a single process, not across restarts.
 * - Notification copy is hardcoded Chinese for batch A — i18n TODO (move to
 *   strings.xml once the notification texts settle).
 * - Token circuit breaker input comes from [tokenProbe], which the DI wires to the usage
 *   ledger (sum of `input_tokens + output_tokens` for the mission's execution
 *   conversation). A probe failure reads as 0 tokens — a metering hiccup must never park
 *   a mission that is otherwise making progress.
 */
class MissionSupervisor(
    private val missionRepository: MissionRepository,
    private val launcher: MissionLauncher,
    private val runEventRepository: RunEventRepository,
    /** runId → 台账状态名（DI 接 AgentRunRepository.getById(id)?.status 的 name）；null=未知。 */
    private val runStatusProbe: RunStatusProbe,
    /**
     * runId → 台账 kind（DI 接 AgentRunRepository.getById(id)?.kind）；null=未知。
     *
     * `mission_dispatch` 的子 run 会带着父任务的 missionId 记账，但它们不是任务轮次：
     * 子代理结束不能触发任务续跑，子代理被取消也不能把父任务停成 IDLE。未知 kind 保守放行
     * （老数据/探针失败时保持批 A 行为）。
     */
    private val runKindProbe: suspend (runId: String) -> String? = { null },
    /** 会话级探针（DI 接 ConversationRepository）。 */
    private val conversationProbe: MissionConversationProbe,
    /** 通知出口（DI 接 NotificationUtil；JVM 测试用记录器）。 */
    private val notify: (title: String, content: String) -> Unit,
    private val appScope: CoroutineScope,
    /**
     * conversationId → tokens spent under it (DI 接 UsageRecordDao.tokensForConversation).
     * Default 0 keeps every pre-existing construction site (and its tests) compiling while
     * turning the breaker off — exactly the batch-A behaviour.
     */
    private val tokenProbe: suspend (conversationId: String) -> Long = { 0L },
    /**
     * 批 F — 检查命令执行器（DI 接 workspace shell）。null = 不跑 hook，行为与批 E 一致。
     */
    private val hookExecutor: MissionHookExecutor? = null,
    /**
     * 批 F — 任务状态文件读取出口（DI 接 WorkspaceRepository）。null = 不做「状态有没有更新」判断。
     */
    private val stateSource: MissionStateSource? = null,
    /**
     * 批 H — 结构化验收执行器（DI 接 workspace shell）。null = 不做机器判定，行为与批 F 一致。
     */
    private val acceptanceVerifier: MissionAcceptanceVerifier? = null,
    /** 批 H — 时钟注入（截止时间判断）；测试钉住时间点。 */
    private val now: () -> Long = { System.currentTimeMillis() },
    /**
     * 批 H — 任务收尾时熄灭它的前台服务登记（DI 接 [MissionForegroundService.stop]）。
     *
     * 只摘掉自己这一条：别的任务还在跑时，前台服务必须继续活着。
     */
    private val onMissionIdle: (missionId: String) -> Unit = {},
) {

    /** run id → ledger status name; null when the run is unknown to the ledger. */
    fun interface RunStatusProbe {
        suspend fun statusOf(runId: String): String?
    }

    /** Conversation-scoped questions the supervisor asks before acting. */
    interface MissionConversationProbe {
        /** 会话最后一条消息是否来自用户（用户在场 → 让位）。 */
        suspend fun userSpokeLast(conversationId: String): Boolean

        /** 会话最后一条 assistant 文本（续跑交接用）；无则 null。 */
        suspend fun lastAssistantText(conversationId: String): String?
    }

    /** Run ids already evaluated by this process (replay guard; empty after a restart). */
    private val processedRuns: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * mission id → consecutive no-progress rounds. In-memory only: a process restart
     * resets the count (the boot scan grants one fresh round before the breaker can
     * trip again — acceptable for batch A; a persisted counter is a later refinement).
     */
    private val stallCounts = ConcurrentHashMap<String, Int>()

    /** The journal subscription started by [start]; null until it ran. */
    private var observeJob: Job? = null

    /**
     * Subscribe to the journal tail and dispatch `RUN_FINISHED` events to
     * [handleRunFinished]. Idempotent: an active subscription makes further calls
     * no-ops. Runs on [appScope] and never throws — any failure inside the collector
     * (including a broken flow) is swallowed; the next process boot re-scans via
     * [onBoot].
     */
    fun start() {
        if (observeJob?.isActive == true) return
        observeJob = runCatching {
            appScope.launch {
                runCatching {
                    runEventRepository.observeRecent(OBSERVE_RECENT_LIMIT).collect { events ->
                        for (event in events) {
                            runCatching {
                                if (
                                    event.type == RunEventType.RUN_FINISHED.name &&
                                    event.missionId != null &&
                                    event.runId != null
                                ) {
                                    handleRunFinished(event)
                                }
                            }
                        }
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * Core dispatch for one finished mission-round run.
     *
     * Deduplicates by run id (first evaluation wins), loads the mission, and only acts
     * while it is still [MissionStatus.ACTIVE]. A run the user cancelled parks the
     * mission back to [MissionExecutionState.IDLE] and is never resurrected; every other
     * ending falls through to [evaluateAndAct], which decides complete / block / resume
     * / yield. Never throws.
     */
    suspend fun handleRunFinished(event: RunEventEntity) {
        runCatching {
            val missionId = event.missionId ?: return
            val runId = event.runId ?: return
            if (!isMissionRound(runId)) return
            if (!processedRuns.add(runId)) return

            val mission = missionRepository.getById(missionId) ?: return
            // Only the mission's CURRENT round may drive continuation. The journal
            // subscription replays a tail on every process start, so an older round's
            // RUN_FINISHED would otherwise launch a duplicate round while the live one is
            // still running. (A null lastRunId is legacy data — stay lenient.)
            val currentRunId = mission.lastRunId
            if (currentRunId != null && currentRunId != runId) return
            if (mission.statusEnum != MissionStatus.ACTIVE) {
                // The user completed / archived the mission while this round was still in
                // flight. Its end is not a continuation trigger; park the posture so a
                // stale RUNNING cannot strand the runtime (or a later boot).
                parkIfRunning(mission)
                return
            }
            // The mission's posture is the runtime's own answer to "is this round live?".
            // A user stop / a terminal transition parks it IDLE while the generation is
            // still winding down; the round's end must not restart what they just ended.
            if (!mission.executionStateEnum.isLive) return

            val status = runStatusProbe.statusOf(runId)
            if (status == RUN_STATUS_CANCELLED) {
                // The user stopped this run — do not resurrect it.
                missionRepository.updateExecutionState(missionId, MissionExecutionState.IDLE)
                return
            }
            // 批 E — the round is parked on a user approval, not finished. The generation
            // job may end while the tool waits (the loop breaks), but the work resumes from
            // the same conversation once the approval is decided; auto-continuing here would
            // race a pending tool and duplicate the round.
            if (mission.executionStateEnum == MissionExecutionState.WAITING_APPROVAL) return

            evaluateAndAct(mission, runId, status)
        }
    }

    /** True when [runId] is a mission execution round (unknown kinds stay eligible). */
    private suspend fun isMissionRound(runId: String): Boolean {
        val kind = runCatching { runKindProbe(runId) }.getOrNull() ?: return true
        return kind == AgentRunKind.Mission.wire
    }

    /** Park a mission whose execution posture still claims a live round. */
    private suspend fun parkIfRunning(mission: MissionEntity) {
        if (mission.executionStateEnum.isLive) {
            missionRepository.updateExecutionState(mission.id, MissionExecutionState.IDLE)
            markIdle(mission.id)
        }
    }

    /**
     * Boot-time orphan scan: for every `ACTIVE` mission whose execution posture is
     * `RUNNING`, check the recorded [MissionEntity.lastRunId]. A run that still looks
     * alive (`null` / queued / awaiting_approval / running) is left alone; a cancelled
     * run parks the mission `IDLE`; a terminal run (succeeded / failed / process_lost)
     * is evaluated exactly like a `RUN_FINISHED` event — that is how a mission resumes
     * after the process died before the run-finished event could be written.
     *
     * Runs each mission in its own `runCatching`; never throws. Expected to be called
     * once per process — the in-memory dedup set is empty after a restart.
     */
    suspend fun onBoot() {
        runCatching {
            val missions = missionRepository.observeAll().first()
            for (mission in missions) {
                runCatching { scanOnBoot(mission) }
            }
        }
    }

    private suspend fun scanOnBoot(mission: MissionEntity) {
        if (mission.status != MissionStatus.ACTIVE.name) return
        if (mission.executionStateEnum != MissionExecutionState.RUNNING) return
        val runId = mission.lastRunId ?: return
        if (!isMissionRound(runId)) return

        when (runStatusProbe.statusOf(runId)) {
            null,
            RUN_STATUS_QUEUED,
            RUN_STATUS_AWAITING_APPROVAL,
            RUN_STATUS_RUNNING -> Unit // alive, or managed by the P5 resume machinery
            RUN_STATUS_CANCELLED -> missionRepository.updateExecutionState(
                mission.id,
                MissionExecutionState.IDLE,
            )
            RUN_STATUS_SUCCEEDED,
            RUN_STATUS_FAILED,
            RUN_STATUS_PROCESS_LOST -> if (processedRuns.add(runId)) evaluateAndAct(mission, runId)
            else -> Unit // unknown future status — stay conservative, do nothing
        }
    }

    /**
     * Gather the finished round's facts, ask [MissionContinuePolicy], and act.
     * Everything below is best-effort; failures are swallowed by the caller chain.
     */
    private suspend fun evaluateAndAct(
        mission: MissionEntity,
        runId: String,
        runStatus: String? = null,
    ) {
        runCatching {
            val events = runEventRepository.observeByRun(runId).first()

            val hasDoneSignal = events.any {
                it.type == RunEventType.NOTE.name && it.title == SIGNAL_DONE_TITLE
            }
            val hasBlockedSignal = events.any {
                it.type == RunEventType.NOTE.name && it.title == SIGNAL_BLOCKED_TITLE
            }
            val hasProgress = events.any {
                it.type == RunEventType.ARTIFACT.name || it.type == RunEventType.TOOL_RESULT.name
            }

            // 批 F — 本轮结束时任务状态文件有没有动过。系统自己看得见的事实，不靠模型自述。
            noteStateUpdate(mission, events, runId)

            // 批 F — 本轮是失败结束的：先跑「失败后检查」采现场，输出进入下一轮交接。
            val failureNote = runAfterFailureHook(mission, runId, runStatus)

            // Stall counter: cleared by any progress in the round, bumped otherwise.
            val stallRounds = if (hasProgress) {
                stallCounts.remove(mission.id)
                0
            } else {
                stallCounts.merge(mission.id, 1, Int::plus) ?: 1
            }

            val userIntervened = mission.conversationId
                ?.let { conversationProbe.userSpokeLast(it) }
                ?: false

            // Token breaker input: everything billed under the mission's execution
            // conversation. A null conversation or a failed probe reads as 0 — metering
            // must never be the reason a healthy mission is parked.
            val totalTokens = mission.conversationId
                ?.let { cid -> runCatching { tokenProbe(cid) }.getOrDefault(0L) }
                ?: 0L

            // 批 A — the user pressed "stop" on this mission: the detail page journals a NOTE
            // titled `user_stop`, and the round ends as a normal COMPLETED run, so without
            // this check the supervisor would "continue" what the user just halted. Treat it
            // like Yield: park the posture and leave the mission alone.
            val userStopped = events.any {
                it.type == RunEventType.NOTE.name && it.title == USER_STOP_TITLE
            }
            if (userStopped) {
                missionRepository.updateExecutionState(mission.id, MissionExecutionState.IDLE)
                markIdle(mission.id)
                return@runCatching
            }

            // 批 H — 设了截止时间的任务，真正的约束是时间而不是轮数。
            // 默认 maxResumeRounds=5 在 30 分钟一轮下只有三个小时，和「跑到 8 小时」直接矛盾；
            // 有了 deadline + 收尾轮 + token 预算三道闸之后，轮数不必再当第二把锁。
            val deadlineBounded = mission.limits.deadlineAt != null
            val input = ContinueInput(
                missionStatus = mission.status,
                hasDoneSignal = hasDoneSignal,
                hasBlockedSignal = hasBlockedSignal,
                resumeCount = mission.resumeCount,
                stallRounds = stallRounds,
                maxResumeRounds = if (deadlineBounded) Int.MAX_VALUE else mission.limits.maxResumeRounds,
                stallThreshold = mission.limits.stallRounds,
                // Token breaker: cumulative usage of the execution conversation. Wired
                // through the usage ledger (see tokenProbe); 0 when unavailable.
                totalTokens = totalTokens,
                tokenBudget = mission.limits.tokenBudget,
                userIntervened = userIntervened,
            )

            when (val decision = MissionContinuePolicy.decide(input)) {
                is ContinueDecision.Complete -> complete(mission, events, runId)
                is ContinueDecision.Block -> block(mission, decision.reason)
                is ContinueDecision.Resume -> resume(mission, checkNote = failureNote)
                is ContinueDecision.Yield, is ContinueDecision.NoOp -> Unit // leave it to the user
            }
        }
    }

    /** Done signal: mission → DONE, notify the delivery summary (payload `note`, or a generic line). */
    private suspend fun complete(
        mission: MissionEntity,
        events: List<RunEventEntity>,
        runId: String? = null,
    ) {
        // 批 F — 完成前检查：模型说 done 只是「申请完成」，检查命令说了才算。
        if (!beforeDoneGate(mission, runId, events)) return
        val summary = doneNote(events) ?: "目标已达成"

        // 批 B.5 — the model declared done: record its self-assessment against the goal
        // list. Reported indices are honoured; omitted / empty means "every goal met".
        recordGoalSelfAssessment(mission, events)

        // 批 B — a trigger-driven mission recurs. A done signal ends THIS occurrence, not the
        // mission: park it back to IDLE (resume budget reset) and keep it ACTIVE so the next
        // fire can launch a fresh round. The user archives it when they no longer want it.
        if (mission.isRecurring) {
            missionRepository.updateExecutionState(mission.id, MissionExecutionState.IDLE)
            markIdle(mission.id)
            missionRepository.resetResumeCount(mission.id)
            missionRepository.logEvent(
                missionId = mission.id,
                type = RunEventType.NOTE,
                title = "occurrence_done",
                detail = summary,
            )
            notify("任务本轮完成：${mission.title}", summary)
            return
        }

        // 批 D — completion gate: when the mission opts in (`requireConfirmation`), a done
        // signal parks it in REVIEW and waits for the user to accept the delivery. The
        // supervisor must not touch it again; approving (REVIEW -> DONE) or sending it back
        // (REVIEW -> ACTIVE) is the user's call from the detail page.
        if (mission.limits.requireConfirmation) {
            missionRepository.transition(
                mission.id,
                MissionStatus.REVIEW,
                note = "mission_report(done) · 待用户确认",
            )
            missionRepository.updateExecutionState(mission.id, MissionExecutionState.IDLE)
            missionRepository.logEvent(
                missionId = mission.id,
                type = RunEventType.NOTE,
                title = "awaiting_review",
                detail = summary,
            )
            notify("任务待确认：${mission.title}", summary)
            markIdle(mission.id)
            return
        }

        missionRepository.transition(mission.id, MissionStatus.DONE, note = "mission_report(done)")
        missionRepository.updateExecutionState(mission.id, MissionExecutionState.IDLE)
        markIdle(mission.id)
        notify("任务完成：${mission.title}", summary)
    }

    /** 批 H — 任务收尾：熄灭前台服务里属于它的那一条（不影响别的任务）。 */
    private fun markIdle(missionId: String) {
        runCatching { onMissionIdle(missionId) }
    }

    /**
     * 批 B.5 — persist the model's goal self-assessment when a round reports done.
     *
     * The `mission_report` call may carry `goals: [0-based indices]`; absent (or empty)
     * means the model claims every goal. Indices outside the list are dropped, so a
     * hallucinated index cannot corrupt the artifact library's ratio. Missions without a
     * goal list are skipped entirely. Best-effort: a write failure never blocks completion.
     */
    private suspend fun recordGoalSelfAssessment(
        mission: MissionEntity,
        events: List<RunEventEntity>,
    ) {
        runCatching {
            val criteria = mission.acceptanceCriteria
            if (criteria.isEmpty()) return@runCatching
            val reported = doneGoals(events)
            val checked = if (reported.isNullOrEmpty()) {
                criteria.indices.toList()
            } else {
                reported.filter { it in criteria.indices }.distinct().sorted()
            }
            missionRepository.setAcceptanceChecked(mission.id, checked)
        }
    }

    /** The `goals` array of the done payload, or null when the model did not send one. */
    private fun doneGoals(events: List<RunEventEntity>): List<Int>? =
        events.firstOrNull {
            it.type == RunEventType.NOTE.name && it.title == SIGNAL_DONE_TITLE
        }
            ?.payloadJson
            ?.let { raw ->
                runCatching {
                    val array = JsonInstant.parseToJsonElement(raw)
                        .jsonObject["goals"] as? JsonArray
                        ?: return@runCatching null
                    array.mapNotNull { it.jsonPrimitive.intOrNull }
                }.getOrNull()
            }

    /** Blocked signal / circuit breaker: mission → BLOCKED, notify the reason. */
    private suspend fun block(mission: MissionEntity, reason: String) {
        missionRepository.transition(mission.id, MissionStatus.BLOCKED, note = reason)
        missionRepository.updateExecutionState(mission.id, MissionExecutionState.BLOCKED)
        markIdle(mission.id)
        notify("任务已停下：${mission.title}", reason)
    }

    /**
     * Auto-continue: spend one resume round, compose the handoff prompt (artifact paths
     * so far + the previous round's closing text) and launch the next round in the SAME
     * conversation. A failed launch only notifies — the mission state is left untouched
     * for the user to handle.
     */
    private suspend fun resume(mission: MissionEntity, checkNote: String? = null) {
        // 批 H — 截止时间优先于一切：轮末检查、续跑预算都不该在超时之后继续花。
        val timePlan = MissionTimeBudget.plan(mission.limits, now())
        if (timePlan.expired) {
            parkExpired(mission)
            return
        }
        // 批 F — 轮末检查：续跑之前先跑。blocking 的失败直接停任务（这就是「一道门」），
        // warn 的失败只把输出带进下一轮交接。
        val roundEnd = runRoundEndHook(mission)
        if (roundEnd != null && roundEnd.blocking) {
            block(mission, roundEnd.describe())
            return
        }
        val newCount = missionRepository.bumpResumeCount(mission.id)
        val artifacts = runEventRepository.observeByMission(mission.id).first()
            .mapNotNull { it.toArtifact()?.path }
        val tail = mission.conversationId?.let { conversationProbe.lastAssistantText(it) }
        val warned = roundEnd?.takeIf { it.failed }?.describe()
        val prompt = MissionContinueComposer.compose(
            HandoffContext(
                title = mission.title,
                goal = mission.description,
                goalsChecked = emptyList(),
                artifacts = artifacts,
                lastRunTail = tail,
                round = newCount,
                maxRounds = mission.limits.maxResumeRounds,
                stateBlock = readStateBlock(mission),
                checkNote = listOfNotNull(checkNote, warned).joinToString("\n\n").ifBlank { null },
                roundDigest = roundDigest(mission),
            )
        )
        val outcome = launcher.launch(
            missionId = mission.id,
            source = LaunchSource.Continue(newCount),
            continuePrompt = prompt,
        )
        if (!outcome.ok) {
            notify("任务续跑失败：${mission.title}", outcome.error ?: "unknown")
        }
    }

    /**
     * 批 H — 到截止时间：把任务交给用户，而不是静默停在那里。
     *
     * 刻意停在 REVIEW 而不是 DONE：没做完就是没做完，让用户看到「时间到了、还差什么」，
     * 由他决定续期还是收下当前产物。
     */
    private suspend fun parkExpired(mission: MissionEntity) {
        val reason = "${MissionTimeBudget.expiredReason(mission.limits)}，已交给你确认"
        runCatching {
            missionRepository.transition(mission.id, MissionStatus.REVIEW, note = reason)
            missionRepository.updateExecutionState(mission.id, MissionExecutionState.IDLE)
        }
        runCatching {
            runEventRepository.emit(
                type = RunEventType.NOTE,
                title = DEADLINE_TITLE,
                detail = reason,
                missionId = mission.id,
            )
        }
        markIdle(mission.id)
        notify("任务到时间了：${mission.title}", reason)
    }

    // ---------------------------------------------------------------------------------------
    // 批 F — 检查命令（hooks）与任务状态
    // ---------------------------------------------------------------------------------------

    /**
     * 批 H — 结构化验收门。
     *
     * 返回 true = 全部通过（或没有配置），可以继续走检查命令 / 落 DONE；
     * false = 已经拦下（发了返工轮，或失败次数用尽 park 给用户）。
     *
     * 三条与 hooks 对齐的规则：没配就放行；跑不起来不阻断（基础设施故障不等于工作不合格）；
     * `MANUAL` 项只记录不判定。唯一新增的语义是 **全部 machine-checkable 项都通过才算过**——
     * hooks 是「最后一道命令」，验收是「逐条对账」，两者职责不同，所以分层而不是合并。
     */
    private suspend fun acceptanceGate(mission: MissionEntity, runId: String?): Boolean {
        val verifier = acceptanceVerifier ?: return true
        val outcomes = runCatching { verifier.verify(mission, runId) }
            .getOrElse { emptyList<AcceptanceOutcome>() }
        if (outcomes.isEmpty()) return true

        outcomes.forEach { outcome ->
            runCatching {
                runEventRepository.emit(
                    type = RunEventType.NOTE,
                    title = ACCEPTANCE_TITLE,
                    detail = outcome.describe(),
                    missionId = mission.id,
                    conversationId = mission.conversationId,
                    runId = runId,
                )
            }
        }
        // 模型自报的 goals 不再单独决定验收勾选：机器能判的以机器为准。
        runCatching {
            val passedIndices = outcomes.mapIndexedNotNull { index, outcome ->
                index.takeIf { outcome.passed == true }
            }
            if (passedIndices.isNotEmpty()) missionRepository.setAcceptanceChecked(mission.id, passedIndices)
        }

        val failed = outcomes.filter { it.blocking }
        if (failed.isEmpty()) {
            runCatching {
                runEventRepository.emit(
                    type = RunEventType.NOTE,
                    title = CHECK_PASSED_TITLE,
                    detail = "验收通过：${outcomes.count { it.passed == true }}/${outcomes.size} 项",
                    missionId = mission.id,
                    conversationId = mission.conversationId,
                    runId = runId,
                )
            }
            return true
        }

        // 记成 check_failed，让「连续失败次数」这套现有熔断继续生效，不另起一套计数。
        runCatching {
            runEventRepository.emit(
                type = RunEventType.NOTE,
                title = CHECK_FAILED_TITLE,
                detail = failed.joinToString("\n") { it.describe() },
                missionId = mission.id,
                conversationId = mission.conversationId,
                runId = runId,
            )
        }
        val failures = countCheckFailures(mission.id)
        val maxReworks = mission.limits.maxCheckFailures.coerceAtLeast(0)
        if (failures > maxReworks) {
            val reason = "验收连续 ${failures} 次未通过，已交给用户"
            missionRepository.transition(mission.id, MissionStatus.REVIEW, note = reason)
            missionRepository.updateExecutionState(mission.id, MissionExecutionState.IDLE)
            markIdle(mission.id)
            notify("任务待确认：${mission.title}", "$reason\n\n${failed.joinToString("\n") { it.describe() }}")
            return false
        }

        val prompt = composeAcceptanceRework(failed, failures, maxReworks)
        val launch = launcher.launch(
            missionId = mission.id,
            source = LaunchSource.Continue(mission.resumeCount + 1),
            continuePrompt = prompt,
        )
        if (!launch.ok) {
            notify("任务返工启动失败：${mission.title}", launch.error ?: "unknown")
        }
        return false
    }

    /** 验收失败后的返工提示词：逐条列出差在哪，要求只补差的部分。 */
    private fun composeAcceptanceRework(
        failed: List<AcceptanceOutcome>,
        attempt: Int,
        maxReworks: Int,
    ): String = buildString {
        appendLine("[系统] 你报告了完成，但验收条件没有通过（第 $attempt/$maxReworks 次返工）。")
        appendLine("未通过的项：")
        failed.forEach { appendLine("- ${it.describe()}") }
        appendLine()
        appendLine("要求：只补这些没通过的部分，不要重做已完成的工作；改完后更新 task-state.md，再调用 mission_report(done)。")
        appendLine("如果这些条件本身无法满足（需求矛盾/环境不支持），调用 mission_report(blocked) 说明原因，不要反复重试同一种做法。")
    }

    /**
     * 完成前检查门。
     *
     * 返回 true = 可以完成落库；false = 已经拦下了（要么发了返工轮，要么把任务 park 给了用户）。
     * 三条刻意的规则：
     * - 没配检查 → 直接放行（老任务行为不变）；
     * - `warn` 的失败 → 记录但放行（用户自己选择不阻断）；
     * - 检查**跑不起来**（没工作区 / shell 崩了）→ 放行。基础设施故障不该判工作不合格。
     */
    private suspend fun beforeDoneGate(
        mission: MissionEntity,
        runId: String?,
        events: List<RunEventEntity>,
    ): Boolean {
        // 批 H — 结构化验收先于检查命令：能机器判定的条件优先，判不了的交给人。
        if (!acceptanceGate(mission, runId)) return false
        val executor = hookExecutor ?: return true
        val hook = MissionHookSpec.runnable(mission.limits, MissionHookWhen.BEFORE_DONE).firstOrNull()
            ?: return true
        val outcome = executor.run(
            mission = mission,
            hook = hook,
            round = mission.resumeCount + 1,
            runId = runId,
            status = RUN_STATUS_SUCCEEDED,
        ) ?: return true

        if (outcome.skippedReason != null) {
            journalHook(mission, outcome, runId, CHECK_FAILED_TITLE)
            return true
        }
        if (!outcome.failed) {
            runCatching {
                runEventRepository.emit(
                    type = RunEventType.NOTE,
                    title = CHECK_PASSED_TITLE,
                    detail = outcome.describe(),
                    missionId = mission.id,
                    conversationId = mission.conversationId,
                    runId = runId,
                )
            }
            return true
        }

        journalHook(mission, outcome, runId, CHECK_FAILED_TITLE)
        if (!outcome.blocking) return true

        val failures = countCheckFailures(mission.id)
        val maxReworks = mission.limits.maxCheckFailures.coerceAtLeast(0)
        if (failures > maxReworks) {
            val reason = "完成前检查连续 ${failures} 次未通过，已交给用户"
            missionRepository.transition(mission.id, MissionStatus.REVIEW, note = reason)
            missionRepository.updateExecutionState(mission.id, MissionExecutionState.IDLE)
            markIdle(mission.id)
            notify("任务待确认：${mission.title}", "$reason\n\n${outcome.describe()}")
            return false
        }

        val prompt = MissionContinueComposer.composeCheckFailure(
            command = hook.command,
            output = outcome.result?.summary().orEmpty(),
            round = failures,
            maxCheckFailures = maxReworks,
        )
        val launch = launcher.launch(
            missionId = mission.id,
            source = LaunchSource.Continue(mission.resumeCount + 1),
            continuePrompt = prompt,
        )
        if (!launch.ok) {
            notify("任务返工启动失败：${mission.title}", launch.error ?: "unknown")
        }
        return false
    }

    /**
     * 轮末检查。返回 null = 没跑（没配 / 没工作区）。
     *
     * 注意它在 [resume] 里、**消耗续跑轮次之前**执行：检查挡住的任务不应该再花掉一轮预算。
     */
    private suspend fun runRoundEndHook(mission: MissionEntity): MissionHookOutcome? {
        val executor = hookExecutor ?: return null
        val hook = MissionHookSpec.runnable(mission.limits, MissionHookWhen.ROUND_END).firstOrNull()
            ?: return null
        val outcome = executor.run(
            mission = mission,
            hook = hook,
            round = mission.resumeCount + 1,
            runId = mission.lastRunId,
        ) ?: return null
        if (outcome.failed || outcome.skippedReason != null) {
            journalHook(mission, outcome, mission.lastRunId, ROUND_CHECK_FAILED_TITLE)
        }
        return outcome
    }

    /**
     * 失败后检查：本轮 run 以失败/超时/进程丢失结束时，采集现场并返回给下一轮交接。
     */
    private suspend fun runAfterFailureHook(
        mission: MissionEntity,
        runId: String,
        runStatus: String?,
    ): String? {
        val executor = hookExecutor ?: return null
        if (!isFailureStatus(runStatus)) return null
        val hook = MissionHookSpec.runnable(mission.limits, MissionHookWhen.AFTER_FAILURE).firstOrNull()
            ?: return null
        val outcome = executor.run(
            mission = mission,
            hook = hook,
            round = mission.resumeCount + 1,
            runId = runId,
            status = runStatus,
        ) ?: return null
        if (outcome.failed || outcome.skippedReason != null) {
            journalHook(mission, outcome, runId, FAILURE_CHECK_FAILED_TITLE)
        }
        return outcome.takeIf { it.failed }?.describe()
    }

    /**
     * 「连续」完成前检查失败的次数：从最后一次通过算起，数之后的失败。
     *
     * 不数历史总和 —— 一个跑了十轮、中间通过过一次的任务，不该因为累计失败次数就把返工额度用完。
     */
    private suspend fun countCheckFailures(missionId: String): Int = runCatching {
        val notes = runEventRepository.observeByMission(missionId).first()
            .filter { it.type == RunEventType.NOTE.name }
        val lastPassed = notes.indexOfLast { it.title == CHECK_PASSED_TITLE }
        notes.drop(lastPassed + 1).count { it.title == CHECK_FAILED_TITLE }
    }.getOrDefault(0)

    private suspend fun journalHook(
        mission: MissionEntity,
        outcome: MissionHookOutcome,
        runId: String?,
        title: String,
    ) {
        runCatching {
            runEventRepository.emit(
                type = RunEventType.NOTE,
                title = title,
                detail = outcome.describe(),
                missionId = mission.id,
                conversationId = mission.conversationId,
                runId = runId,
            )
        }
    }

    /**
     * 本轮结束时 task-state.md 有没有更新：对比开跑时写下的指纹。
     *
     * 只记录，不改判：状态没更新本身不等于没干活（有的轮次只是在验算），但连续几轮都这样就该看见。
     */
    private suspend fun noteStateUpdate(
        mission: MissionEntity,
        events: List<RunEventEntity>,
        runId: String,
    ) {
        val source = stateSource ?: return
        val workspaceId = mission.workspaceIdOrNull ?: return
        val before = events.firstOrNull {
            it.type == RunEventType.NOTE.name && it.title == MissionLauncher.STATE_HASH_TITLE
        }?.detail ?: return
        val after = runCatching { MissionTaskState.fingerprint(source.read(workspaceId)) }.getOrNull() ?: return
        if (after != before) return
        runCatching {
            runEventRepository.emit(
                type = RunEventType.NOTE,
                title = STATE_UNCHANGED_TITLE,
                detail = "本轮结束时 ${MissionTaskState.PATH} 没有变化",
                missionId = mission.id,
                conversationId = mission.conversationId,
                runId = runId,
            )
        }
    }

    /** 下一轮要注入的任务状态块；没配 / 没工作区 → null。 */
    private suspend fun readStateBlock(mission: MissionEntity): String? {
        val source = stateSource ?: return null
        val workspaceId = mission.workspaceIdOrNull ?: return null
        val text = runCatching { source.read(workspaceId) }.getOrNull()
        return MissionTaskState.composeBlock(text)
    }

    /**
     * 系统侧的本轮事实：run 状态、工具调用次数、新增产物数。
     *
     * 存在的意义是给模型一个**不是它自己写的**进度快照 —— 自述会美化，计数不会。
     */
    private suspend fun roundDigest(mission: MissionEntity): String? = runCatching {
        val runId = mission.lastRunId
        if (runId == null) {
            null
        } else {
            val events = runEventRepository.observeByRun(runId).first()
            val tools = events.count { it.type == RunEventType.TOOL_RESULT.name }
            val artifacts = events.count { it.type == RunEventType.ARTIFACT.name }
            val status = events.firstOrNull { it.type == RunEventType.RUN_FINISHED.name }?.detail
            "状态：${status ?: "未知"}；工具调用：$tools 次；本轮新增产物：$artifacts 件"
        }
    }.getOrNull()

    /** 台账状态名是否表示「这一轮不是正常跑完的」。 */
    private fun isFailureStatus(status: String?): Boolean {
        val normalized = status ?: return false
        return normalized !in setOf(
            RUN_STATUS_SUCCEEDED,
            RUN_STATUS_RUNNING,
            RUN_STATUS_QUEUED,
            RUN_STATUS_AWAITING_APPROVAL,
            RUN_STATUS_CANCELLED,
        )
    }

    /** The `note` field of the latest `mission_report: done` payload, when parseable. */
    private fun doneNote(events: List<RunEventEntity>): String? =
        events.firstOrNull {
            it.type == RunEventType.NOTE.name && it.title == SIGNAL_DONE_TITLE
        }
            ?.payloadJson
            ?.let { raw ->
                runCatching {
                    JsonInstant.parseToJsonElement(raw)
                        .jsonObject["note"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.getOrNull()
            }
            ?.takeIf { it.isNotBlank() }
}
