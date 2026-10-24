package me.rerere.rikkahub.service.mission

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum
import me.rerere.rikkahub.data.mission.AcceptanceCheckSpec
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionExecutionState
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.MissionSnapshotStore
import me.rerere.rikkahub.data.mission.MissionTaskState
import me.rerere.rikkahub.data.mission.MissionTimeBudget
import me.rerere.rikkahub.data.mission.acceptanceCriteria
import me.rerere.rikkahub.data.mission.limits
import me.rerere.rikkahub.data.mission.statusEnum
import me.rerere.rikkahub.data.mission.workspaceIdOrNull
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.service.run.RunRequest
import me.rerere.rikkahub.service.run.RunService
import kotlin.uuid.Uuid

/** Kickoff instruction appended to the mission directive on the Create launch (built-in first round). */
private const val CREATE_KICKOFF =
    "现在开始执行这个任务：先给出简短计划，然后立即动手。产出放进工作区。"

/** Wall-clock fallback for a mission round when the mission carries no usable limit. */
private const val LAUNCH_TIMEOUT_MS: Long = 30L * 60_000L

/** Where a launch originated — the wish-style first run ("Create") or a supervisor continuation. */
sealed interface LaunchSource {

    /** 许愿式首跑：无执行会话时由 RunService 新建（之后回写 mission），prompt 用内置守则 + 启动指令。 */
    object Create : LaunchSource

    /**
     * 自动续跑第 [round] 轮（1-based）。prompt 由调用方（Supervisor）用
     * [MissionContinueComposer.compose] 组装好后经 `continuePrompt` 传入。
     */
    data class Continue(val round: Int) : LaunchSource

    /**
     * 批 B — 定时 / 触发唤醒的一轮。复用已有执行会话（没有则新建），prompt 是任务守则 +
     * 触发说明。任务必须是 ACTIVE；[label] 用于时间线与开场指令（如「定时 09:00」「Wi-Fi 连接」）。
     */
    data class Trigger(val label: String) : LaunchSource
}

/** Result of [MissionLauncher.launch]; `ok = false` carries a human-readable [error]. */
data class LaunchOutcome(val ok: Boolean, val runId: String? = null, val error: String? = null)

/**
 * Batch A — the single entry point that turns a mission row into an execution run.
 *
 * One [launch] does the whole dance: read + validate the mission → resolve the execution
 * conversation → compose the prompt → resolve the assistant → [RunService.submit] →
 * best-effort bookkeeping (backfill conversation, execution state, session registry,
 * launch journal entry).
 *
 * Create vs Continue:
 * - [LaunchSource.Create]: wish-style first run. With no (or an unparseable) mission
 *   conversation, submit is asked to create a fresh conversation and the handle's id is
 *   backfilled onto the mission; an existing conversation id is reused as-is.
 * - [LaunchSource.Continue]: the supervisor loop re-enters the SAME conversation. Refused
 *   (without submitting) when the mission is not ACTIVE, has no usable conversation, or
 *   the caller did not supply `continuePrompt`.
 *
 * The mission directive ([MissionContinueComposer.composeDirective]) is armed as the
 * conversation's system addendum on every launch so each round's generation sees it
 * without the transcript carrying scaffolding (awaitTerminal clears it at run end; the
 * next launch re-arms it).
 *
 * Bookkeeping is deliberately best-effort — a journal or backfill hiccup must never turn
 * a successfully started run into a launch failure. Fatal problems (mission missing, no
 * assistant) come back as [LaunchOutcome.ok] = false. [launch] never throws.
 */
class MissionLauncher(
    private val missionRepository: MissionRepository,
    private val runService: RunService,
    private val runEventRepository: RunEventRepository,
    /** mission.assistantId (nullable) → the assistant Uuid to run with; fallback logic lives in DI. */
    private val assistantResolver: suspend (String?) -> Uuid?,
    /**
     * 批 C — 工作流程文档的读取出口（DI 接 WorkspaceRepository）；null = 不注入流程。
     * 测试与旧构造点不传时行为与批 B 完全一致。
     */
    private val workflowSource: MissionWorkflowSource? = null,
    /**
     * 批 E — 应用级 scope，用来在后台驱动本轮 run 的终结。
     *
     * `RunService.submit` 只是把生成任务点起来；台账结算、headless 标记释放和
     * `RUN_FINISHED` 事件都发生在 `awaitTerminal` 里，而 `RUN_FINISHED` 是 MissionSupervisor
     * 唯一的主事件源。批 A 起一直没人调用它，导致任务轮次跑完也不会触发「完成 / 续跑 / 熔断」
     * 判定（只有重启时的 onBoot 扫描能兜到）。这里给无头轮次补上后台 await；null（测试和旧构造点）
     * 保持原行为。
     */
    private val appScope: CoroutineScope? = null,
    /**
     * 批 F — 任务状态文件（`task-state.md`）的读取出口，DI 接 WorkspaceRepository；null = 不注入
     * 状态块（测试与旧构造点保持批 E 行为）。
     */
    private val stateSource: MissionStateSource? = null,
    /** 批 F — 轮次快照。null = 不快照。 */
    private val snapshotStore: MissionSnapshotStore? = null,
    /** 批 F — 把工作区 id 解析成 FILES 区目录（快照拷贝的源）；null = 不快照。 */
    private val workspaceFiles: MissionWorkspaceFiles? = null,
    /**
     * 批 H — 时钟注入。截止时间、收尾窗口、单轮超时全都要「现在几点」；测试钉住时间点，
     * 生产用系统时钟。
     */
    private val now: () -> Long = { System.currentTimeMillis() },
    /**
     * 批 H — 轮次开始时点亮任务前台服务（DI 接 [MissionForegroundService.start]）。
     *
     * 用回调而不是直接依赖 Service：launcher 是纯逻辑层，JVM 测试没有 Context，
     * 也不能拉起前台服务。默认空实现让所有旧构造点行为不变。
     */
    private val onRoundStarted: (missionId: String, label: String) -> Unit = { _, _ -> },
) {

    /** Launch (or continue) mission [missionId]; see the class KDoc for the full dance. */
    suspend fun launch(
        missionId: String,
        source: LaunchSource,
        continuePrompt: String? = null,
    ): LaunchOutcome {
        return try {
            val mission = missionRepository.getById(missionId)
                ?: return LaunchOutcome(ok = false, error = "Mission not found: $missionId")

            if (source !is LaunchSource.Create && mission.statusEnum != MissionStatus.ACTIVE) {
                return LaunchOutcome(
                    ok = false,
                    error = "Mission is ${mission.status}, not ACTIVE — launch refused",
                )
            }

            // Resolve the conversation this run targets. An id that fails to parse counts as
            // "no conversation" (Create: fresh one; Continue: refusal — handled below).
            val missionConversation = mission.conversationId
                ?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
            val conversationUuid: Uuid? = when (source) {
                LaunchSource.Create, is LaunchSource.Trigger -> missionConversation
                is LaunchSource.Continue -> missionConversation
                    ?: return LaunchOutcome(
                        ok = false,
                        error = "Mission $missionId has no usable execution conversation; continue refused",
                    )
            }
            val directive = MissionContinueComposer.composeDirective(
                mission.title,
                mission.description,
                mission.acceptanceCriteria,
            )
            // 批 H — 时间预算：到点不启动、接近截止进收尾轮。判断放在 launcher 而不是督导，
            // 因为 Trigger（定时/事件唤醒）也可能绕过督导直接起轮。
            val limits = mission.limits
            val timePlan = MissionTimeBudget.plan(
                limits = limits,
                now = now(),
                defaultTimeoutMs = LAUNCH_TIMEOUT_MS,
            )
            if (timePlan.expired) {
                runCatching {
                    runEventRepository.emit(
                        type = RunEventType.NOTE,
                        title = DEADLINE_TITLE,
                        detail = MissionTimeBudget.expiredReason(limits),
                        missionId = missionId,
                    )
                }
                return LaunchOutcome(
                    ok = false,
                    error = "${MissionTimeBudget.expiredReason(limits)}，未启动新一轮",
                )
            }
            val acceptanceBlock = AcceptanceCheckSpec.composePromptBlock(limits.acceptanceChecks)
            val wrapUpBlock = if (timePlan.wrapUp && timePlan.remainingMs != null) {
                MissionTimeBudget.wrapUpDirective(timePlan.remainingMs)
            } else {
                ""
            }
            // 批 F — 跨轮状态：每轮都注入。读不到就退化成「先建档」的指令，绝不让一轮任务因为
            // 状态文件缺失而失败。
            val stateText = mission.workspaceIdOrNull?.let { workspaceId ->
                runCatching { stateSource?.read(workspaceId) }.getOrNull()
            }
            val stateBlock = stateSource?.let { MissionTaskState.composeBlock(stateText) }.orEmpty()
            val prompt = when (source) {
                LaunchSource.Create -> joinBlocks(
                    directive,
                    acceptanceBlock,
                    missionWorkflowBlock(mission),
                    stateBlock,
                    wrapUpBlock,
                    CREATE_KICKOFF,
                )
                is LaunchSource.Trigger ->
                    joinBlocks(
                        directive,
                        acceptanceBlock,
                        missionWorkflowBlock(mission),
                        stateBlock,
                        wrapUpBlock,
                        "[系统] 本任务的开始方式被触发（${source.label}）。" +
                            "现在开始执行本轮任务：先给简短计划，然后立即动手；完成后调用 mission_report(done)。",
                    )
                is LaunchSource.Continue -> {
                    val continueText = continuePrompt
                        ?: return LaunchOutcome(
                            ok = false,
                            error = "Continue refused: continuePrompt is required",
                        )
                    joinBlocks(
                        missionWorkflowContinueHint(mission),
                        acceptanceBlock,
                        continueText,
                        stateBlock,
                        wrapUpBlock,
                    )
                }
            }

            val assistantId = assistantResolver(mission.assistantId)
                ?: return LaunchOutcome(
                    ok = false,
                    error = "No assistant available for mission $missionId",
                )

            // Approval posture → headless flag: SEMI / MANUAL keep the human in the loop;
            // AUTO, unset and anything unrecognised run headless (inherit-friendly default).
            val headless = when (mission.approvalMode?.trim()?.uppercase()) {
                "SEMI", "MANUAL" -> false
                else -> true
            }

            // 批 F — 轮次快照必须在 submit 之前：这一轮会改工作区，基线只能拍在动手之前。
            // 快照失败/跳过绝不阻断任务（记录一条 journal 就够）。
            captureRoundSnapshot(mission)

            // Arm the mission directive and bind the mission↔conversation mapping before submit
            // when the conversation is known, so the generation that submit kicks off is
            // guaranteed to see both. For a fresh conversation the id only exists inside
            // RunService.submit; that path registers the binding itself before dispatching.
            conversationUuid?.let { conv ->
                runCatching { ConversationSystemAddendum.set(conv, directive) }
                runCatching { MissionSessions.register(conv.toString(), missionId) }
            }

            val handle = runService.submit(
                RunRequest(
                    assistantId = assistantId,
                    prompt = prompt,
                    title = "任务：${mission.title}",
                    kind = AgentRunKind.Mission,
                    missionId = missionId,
                    conversationId = conversationUuid,
                    headless = headless,
                    timeoutMs = timePlan.timeoutMs,
                )
            )

            // ---- post-submit bookkeeping — best-effort, never fails the launch --------------
            // A fresh conversation only materialises inside submit; arm its directive too
            // (idempotent for the reused-conversation case).
            runCatching { ConversationSystemAddendum.set(handle.conversationId, directive) }
            // Backfill whenever submit did not hand back the conversation we asked for: a
            // mission with no conversation yet, or a stale id that no longer resolves (submit
            // then creates a fresh conversation). Without the second case the mission keeps
            // pointing at the dead conversation, so every later round reports into nothing.
            if (handle.conversationId != conversationUuid) {
                runCatching {
                    missionRepository.updateConversation(missionId, handle.conversationId.toString())
                }
            }
            runCatching {
                missionRepository.updateExecutionState(
                    missionId,
                    MissionExecutionState.RUNNING,
                    lastRunId = handle.id,
                )
            }
            runCatching { MissionSessions.register(handle.conversationId.toString(), missionId) }
            runCatching {
                runEventRepository.emit(
                    type = RunEventType.NOTE,
                    title = "launch",
                    detail = when (source) {
                        LaunchSource.Create -> "create"
                        is LaunchSource.Continue -> "continue round ${source.round}"
                        is LaunchSource.Trigger -> "trigger: ${source.label}"
                    },
                    missionId = missionId,
                    conversationId = handle.conversationId.toString(),
                    runId = handle.id,
                )
            }
            // 批 H — 收尾轮留痕：时间线上能看出「这轮是被截止时间逼出来的」，而不是模型自己
            // 决定收工。detail 里带剩余时间，复盘时能算清是收尾太早还是太晚。
            if (timePlan.wrapUp && timePlan.remainingMs != null) {
                runCatching {
                    runEventRepository.emit(
                        type = RunEventType.NOTE,
                        title = WRAP_UP_TITLE,
                        detail = "收尾轮 · 剩余 ${MissionTimeBudget.formatRemaining(timePlan.remainingMs)}",
                        missionId = missionId,
                        conversationId = handle.conversationId.toString(),
                        runId = handle.id,
                    )
                }
            }
            // 批 H — 点亮任务前台服务：从这里开始到任务收尾，进程不该被厂商省电策略回收。
            runCatching {
                val roundLabel = when (source) {
                    LaunchSource.Create -> "启动"
                    is LaunchSource.Continue -> "第 ${source.round} 轮"
                    is LaunchSource.Trigger -> source.label
                }
                onRoundStarted(missionId, "${mission.title} · $roundLabel")
            }
            // 批 F — 记下本轮开跑时的状态指纹，督导用它判断这一轮到底有没有更新 task-state.md。
            runCatching {
                runEventRepository.emit(
                    type = RunEventType.NOTE,
                    title = STATE_HASH_TITLE,
                    detail = MissionTaskState.fingerprint(stateText),
                    missionId = missionId,
                    conversationId = handle.conversationId.toString(),
                    runId = handle.id,
                )
            }

            // 批 E — 无头轮次只有在有人 awaitTerminal 时才会结算台账、释放 headless 标记并写
            // RUN_FINISHED（督导的唯一主事件源）。交互式（SEMI/MANUAL）轮次可能停在工具审批上：
            // 生成任务会在审批处结束，但工作尚未结束，因此不在后台 watch，交给用户操作。
            if (headless) {
                appScope?.launch {
                    runCatching { runService.awaitTerminal(handle) }
                }
            }

            LaunchOutcome(ok = true, runId = handle.id)
        } catch (t: Throwable) {
            LaunchOutcome(
                ok = false,
                error = "launch failed: ${t.message ?: t::class.simpleName ?: "unknown error"}",
            )
        }
    }

    /**
     * 批 C — the workflow section of a fresh round's prompt.
     *
     * - pinned (`missions.workflow_path` set): read the document and inject its body. A
     *   missing/unreadable file degrades to a warning line instead of failing the launch —
     *   a deleted playbook must not block the mission.
     * - chooser (no path): list `workflows/ 下的 .md` and tell the model to pick one.
     * - no source, no workspace, or no documents: inject nothing.
     */
    private suspend fun missionWorkflowBlock(mission: MissionEntity): String {
        val source = workflowSource ?: return ""
        val workspaceId = mission.workspaceId?.takeIf { it.isNotBlank() } ?: return ""

        val pinned = mission.workflowPath?.takeIf { it.isNotBlank() }
        if (pinned != null) {
            val content = runCatching { source.read(workspaceId, pinned) }.getOrNull()
            return if (content.isNullOrBlank()) {
                "[工作流程] 任务指定的流程文档 $pinned 无法读取（可能已被删除）；请按任务目标自行推进。"
            } else {
                MissionWorkflowDocs.composePinned(pinned, content)
            }
        }

        val names = runCatching { source.list(workspaceId) }.getOrNull().orEmpty()
            .filter { MissionWorkflowDocs.isWorkflowDoc(MissionWorkflowDocs.pathOf(it)) }
        return MissionWorkflowDocs.composeChooser(names)
    }

    /**
     * 批 C — continuation rounds only carry a one-line reminder: the playbook body is
     * already in the transcript (injected on the first/trigger round), so re-injecting it
     * every round would just burn tokens.
     */
    private fun missionWorkflowContinueHint(mission: MissionEntity): String =
        mission.workflowPath?.takeIf { it.isNotBlank() }
            ?.let { MissionWorkflowDocs.composeContinueHint(it) }
            ?: ""

    /** Joins prompt sections, dropping blanks so no stray double-newlines end up in a prompt. */
    private fun joinBlocks(vararg blocks: String): String =
        blocks.filter { it.isNotBlank() }.joinToString("\n\n")

    /**
     * 批 F — 拍一张本轮开始前的工作区快照，并在 journal 上留痕。
     *
     * 全程 best-effort：没有工作区、没有配置快照存储、拷贝失败 —— 一律只记一条 NOTE，
     * 任务照常开跑。快照是「后悔药」，不是前置条件。
     */
    private suspend fun captureRoundSnapshot(mission: MissionEntity) {
        val store = snapshotStore ?: return
        val files = workspaceFiles ?: return
        val workspaceId = mission.workspaceIdOrNull ?: return
        val dir = runCatching { files.filesDir(workspaceId) }.getOrNull() ?: return
        when (val result = runCatching { store.capture(dir, mission.id) }.getOrNull()) {
            is MissionSnapshotStore.CaptureResult.Saved -> runCatching {
                runEventRepository.emit(
                    type = RunEventType.NOTE,
                    title = SNAPSHOT_TITLE,
                    detail = "第 ${result.snapshot.round} 轮开始前 · " +
                        "${result.snapshot.fileCount} 个文件 · ${result.snapshot.totalBytes / 1024}KB",
                    missionId = mission.id,
                )
            }

            is MissionSnapshotStore.CaptureResult.Skipped -> runCatching {
                runEventRepository.emit(
                    type = RunEventType.NOTE,
                    title = SNAPSHOT_SKIPPED_TITLE,
                    detail = result.reason,
                    missionId = mission.id,
                )
            }

            else -> Unit // Empty：工作区还是空的，没有可存的东西
        }
    }

    companion object {
        /** 轮次快照的 journal 标题（详情页时间线里显示为一条记录）。 */
        const val SNAPSHOT_TITLE: String = "snapshot"

        /** 快照被跳过时的 journal 标题。 */
        const val SNAPSHOT_SKIPPED_TITLE: String = "snapshot_skipped"

        /** 本轮开跑时 task-state.md 的指纹；督导用它对比轮末状态。 */
        const val STATE_HASH_TITLE: String = "state_hash"

        /** 批 H — 到截止时间、被拒绝启动时的 journal 标题。 */
        const val DEADLINE_TITLE: String = "deadline"

        /** 批 H — 收尾轮的 journal 标题。 */
        const val WRAP_UP_TITLE: String = "wrap_up"
    }
}
