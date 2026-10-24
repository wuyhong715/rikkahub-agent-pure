package me.rerere.rikkahub.data.mission

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.runtime.util.JsonInstant

/**
 * Workbench Mission — lifecycle status for a mission row.
 *
 * The state machine allows missions to flow from active work through review to completion
 * (and eventual archival). Terminal states ([DONE], [ARCHIVED]) cannot be left except via
 * the single [DONE] → [ARCHIVED] edge.
 */
enum class MissionStatus(val isTerminal: Boolean) {
    ACTIVE(false),
    BLOCKED(false),
    REVIEW(false),
    DONE(true),
    ARCHIVED(true);

    /**
     * Whether a transition from `this` to [to] is legal under the mission state machine.
     */
    fun canTransitionTo(to: MissionStatus): Boolean = when (this) {
        ACTIVE -> to in setOf(BLOCKED, REVIEW, DONE, ARCHIVED)
        BLOCKED -> to in setOf(ACTIVE, ARCHIVED)
        REVIEW -> to in setOf(ACTIVE, DONE, ARCHIVED)
        DONE -> to == ARCHIVED
        ARCHIVED -> false
    }
}

/**
 * 批 A — execution posture of a mission, stored as the name in [MissionEntity.executionState].
 *
 * Kept separate from [MissionStatus] on purpose: status is the *workflow* state the user
 * manages (active → blocked/review → done), while this tracks what the runtime is doing
 * right now (idle / a run in flight / parked on a tool approval / stopped by the circuit
 * breaker). One mission can be ACTIVE status while its execution state cycles many times.
 */
enum class MissionExecutionState {
    IDLE,
    RUNNING,
    WAITING_APPROVAL,
    BLOCKED,
}

/**
 * A workbench mission — a tracked unit of work with acceptance criteria, lifecycle
 * status, and a timeline of events (see [me.rerere.rikkahub.data.runevent.RunEventEntity],
 * the unified run-event journal that superseded the day-one `mission_events` table).
 *
 * JSON columns ([acceptanceJson], [acceptanceCheckedJson]) are decoded via the
 * convenience extensions ([acceptanceCriteria], [acceptanceChecked]) using
 * [JsonInstant].
 */
@Entity(
    tableName = "missions",
    indices = [
        Index("status"),
        Index("conversation_id"),
        Index("updated_at"),
    ],
)
data class MissionEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("description")
    val description: String = "",
    @ColumnInfo("status")
    val status: String = "ACTIVE",
    @ColumnInfo("acceptance_json")
    val acceptanceJson: String = "[]",
    @ColumnInfo("acceptance_checked_json")
    val acceptanceCheckedJson: String = "[]",
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("assistant_id")
    val assistantId: String? = null,
    @ColumnInfo("artifact_count")
    val artifactCount: Int = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("completed_at")
    val completedAt: Long? = null,
    // -------------------------------------------------------------------------------------
    // 批 A（任务运行时，v35）— execution & configuration columns
    // -------------------------------------------------------------------------------------
    /** Execution workspace (WorkspaceEntity.id); null = not bound to a workspace. */
    @ColumnInfo("workspace_id")
    val workspaceId: String? = null,
    /** Per-mission approval posture (ApprovalMode name); null = inherit the global setting. */
    @ColumnInfo("approval_mode")
    val approvalMode: String? = null,
    /** Per-mission runtime-limit overrides ([MissionLimits] JSON); null = global defaults. */
    @ColumnInfo("limits_json")
    val limitsJson: String? = null,
    /** Auto-continue rounds already consumed (circuit-breaker counter; reset on user action). */
    @ColumnInfo("resume_count", defaultValue = "0")
    val resumeCount: Int = 0,
    /** Latest execution run id (= agent_runs.id); null before the first launch. */
    @ColumnInfo("last_run_id")
    val lastRunId: String? = null,
    /** Execution posture, stored as [MissionExecutionState]'s name. Default "idle". */
    @ColumnInfo("execution_state", defaultValue = "idle")
    val executionState: String = "idle",
    // -------------------------------------------------------------------------------------
    // 批 B（开始方式，v36）— trigger / schedule columns
    // -------------------------------------------------------------------------------------
    /**
     * Start mode: serialized [me.rerere.rikkahub.workflow.model.TriggerSpec] JSON. `null` means
     * the mission only ever runs when the user presses "start" (the wish-style immediate path).
     * A non-null value makes the mission **recurring**: every fire of the trigger starts a new
     * execution round, and a `mission_report(done)` ends the occurrence rather than the mission.
     */
    @ColumnInfo("trigger_json")
    val triggerJson: String? = null,
    /** Whether the trigger is armed. Paused missions stay ACTIVE but are not scheduled. */
    @ColumnInfo("trigger_enabled", defaultValue = "1")
    val triggerEnabled: Boolean = true,
    /** Minimum gap between two trigger fires, seconds. 0 = no cooldown. */
    @ColumnInfo("cooldown_seconds", defaultValue = "0")
    val cooldownSeconds: Int = 0,
    /** Max fires per local day. `null` = unlimited. */
    @ColumnInfo("max_runs_per_day")
    val maxRunsPerDay: Int? = null,
    /** Wall-clock of the last accepted trigger fire. */
    @ColumnInfo("last_fired_at")
    val lastFiredAt: Long? = null,
    /** Fires already consumed on [firesDay]. */
    @ColumnInfo("fires_today", defaultValue = "0")
    val firesToday: Int = 0,
    /** Local calendar day (`yyyy-MM-dd`) that [firesToday] belongs to. */
    @ColumnInfo("fires_day")
    val firesDay: String? = null,
    /** Next expected fire for time-based triggers (display only; WorkManager owns the real one). */
    @ColumnInfo("next_run_at")
    val nextRunAt: Long? = null,
    // -------------------------------------------------------------------------------------
    // 批 C（工作流程，v37）— the .md playbook the mission follows
    // -------------------------------------------------------------------------------------
    /**
     * Path of a workflow document inside the mission's workspace, relative to the FILES
     * area root (e.g. `workflows/weekly-report.md`). `null` means "let the model choose":
     * the launch prompt lists the workspace's `workflows/ 下的 .md` files and the model picks
     * one (or works without one). The path is stored, never the content — editing the .md
     * changes the next round without touching the mission row.
     */
    @ColumnInfo("workflow_path")
    val workflowPath: String? = null,
)

// ---------------------------------------------------------------------------
// Convenience extensions — decode JSON columns via JsonInstant.
// ---------------------------------------------------------------------------

/** Decode the stored status string into a [MissionStatus], defaulting to [MissionStatus.ACTIVE]. */
val MissionEntity.statusEnum: MissionStatus
    get() = MissionStatus.entries.firstOrNull { it.name == status }
        ?: MissionStatus.ACTIVE

/** Decode [MissionEntity.acceptanceJson] into a list of acceptance criteria strings. */
val MissionEntity.acceptanceCriteria: List<String>
    get() = runCatching {
        JsonInstant.decodeFromString<List<String>>(acceptanceJson)
    }.getOrDefault(emptyList())

/** Decode [MissionEntity.acceptanceCheckedJson] into a list of checked criterion indices. */
val MissionEntity.acceptanceChecked: List<Int>
    get() = runCatching {
        JsonInstant.decodeFromString<List<Int>>(acceptanceCheckedJson)
    }.getOrDefault(emptyList())

/**
 * 批 A — per-mission runtime limits with balanced defaults ("均衡值"), overridable per mission
 * via [MissionEntity.limitsJson] and globally via the task settings screen.
 *
 * - [maxResumeRounds]: the auto-continue circuit breaker — how many extra rounds the supervisor
 *   may start after a round ends without a done/blocked signal.
 * - [stallRounds]: consecutive no-progress rounds (no new artifact, no tool result) that stop
 *   the loop even before [maxResumeRounds] is reached.
 * - [tokenBudget]: cumulative token ceiling for the whole mission (checked when the usage
 *   aggregation lands; kept here so the wire format does not change later).
 * - [requireConfirmation]: 批 D — when true, a model-declared `done` parks the mission in
 *   [MissionStatus.REVIEW] instead of closing it, so the user confirms the delivery first.
 *   Recurring (trigger-driven) missions ignore it: a done signal only ends the occurrence.
 *   Default false keeps the batch-A/B behaviour for existing rows (JSON decode fills the
 *   default, so no schema migration is involved).
 */
@Serializable
data class MissionLimits(
    val maxResumeRounds: Int = 5,
    val stallRounds: Int = 2,
    val tokenBudget: Long = 500_000L,
    val requireConfirmation: Boolean = false,
    /**
     * 批 F — 连续多少次「完成前检查失败」的返工之后把任务交给用户。
     *
     * `mission_report(done)` 不再等于完成：先跑 [MissionHookWhen.BEFORE_DONE] 的检查，失败就把
     * 输出当返工要求再跑一轮。同一个任务连续失败到这次数就停手（park 到 REVIEW），避免无限返工
     * 烧钱。0 表示一次都不返工（检查失败直接交人）。
     */
    val maxCheckFailures: Int = 2,
    /**
     * 批 F — 任务自带的检查命令（[MissionHook]）。空列表 = 没有任何检查，行为与批 E 一致。
     *
     * 存进这个 JSON 列是刻意的：加字段不迁移 schema，旧任务读出来就是空列表。
     */
    val hooks: List<MissionHook> = emptyList(),
    /**
     * 批 H — 每一轮（一次 run）的墙钟上限，分钟。
     *
     * 此前是 `MissionLauncher` 里的 30 分钟常量，长任务没法按任务类型调整：调研类适合短轮次
     * 多衔接，写代码适合一轮跑久一点。夹在 [MissionTimeBudget.MIN_ROUND_MIN]–[MAX_ROUND_MIN]
     * 之间；无截止时间的任务用它当单轮上限，有截止时间的任务取它与剩余时间的较小值。
     */
    val roundTimeoutMinutes: Int = MissionTimeBudget.DEFAULT_ROUND_MIN,
    /**
     * 批 H — 任务截止时间（epoch millis）。null = 不限时。
     *
     * 到达之后：正在跑的一轮不再续跑，督导把任务 park 到 REVIEW 并把剩余产物/状态交给用户；
     * 截止前 [wrapUpMinutes] 分钟内启动的一轮被标记为「收尾轮」，提示词要求只做总结与交付，
     * 不再开新战线。这是把「跑 8 小时」从愿望变成可控承诺的那一半。
     */
    val deadlineAt: Long? = null,
    /**
     * 批 H — 截止前多少分钟进入收尾轮。0 表示不设收尾窗口（到点硬停）。
     */
    val wrapUpMinutes: Int = MissionTimeBudget.DEFAULT_WRAPUP_MIN,
    /**
     * 批 H — 结构化验收条件（[AcceptanceCheck]）。空列表 = 沿用批 A 的字符串验收（模型自评）。
     *
     * 与 [hooks] 同在 limits_json 里，同样不触碰 schema。有结构化验收时，
     * `mission_report(done)` 先过验收判定：全部通过才落 DONE，否则把未通过项当返工要求。
     */
    val acceptanceChecks: List<AcceptanceCheck> = emptyList(),
) {
    /** 只保留可执行的 hook / 验收项（丢掉空命令、越界超时、空目标），写库前统一走这里。 */
    fun sanitized(): MissionLimits = copy(
        hooks = MissionHookSpec.sanitize(hooks),
        roundTimeoutMinutes = MissionTimeBudget.clampRoundMinutes(roundTimeoutMinutes),
        wrapUpMinutes = MissionTimeBudget.clampWrapUpMinutes(wrapUpMinutes),
        acceptanceChecks = AcceptanceCheckSpec.sanitize(acceptanceChecks),
    )
}

/** Decode [MissionEntity.limitsJson]; null / blank / malformed → balanced defaults. */
val MissionEntity.limits: MissionLimits
    get() = runCatching {
        limitsJson?.takeIf { it.isNotBlank() }
                ?.let { JsonInstant.decodeFromString<MissionLimits>(it) }
    }.getOrNull() ?: MissionLimits()

/** 绑定的执行工作区 id；空白字符串一律当作「没有工作区」。 */
val MissionEntity.workspaceIdOrNull: String?
    get() = workspaceId?.takeIf { it.isNotBlank() }

/** Decode [MissionEntity.executionState] into a [MissionExecutionState], defaulting to [MissionExecutionState.IDLE]. */
val MissionEntity.executionStateEnum: MissionExecutionState
    get() = MissionExecutionState.entries.firstOrNull { it.name == executionState }
        ?: MissionExecutionState.IDLE

/**
 * True while the posture says a round is live (running, or parked on a tool approval).
 * IDLE / BLOCKED mean the runtime has no round to continue — the supervisor must not
 * restart one just because a generation was still winding down.
 */
val MissionExecutionState.isLive: Boolean
    get() = this == MissionExecutionState.RUNNING || this == MissionExecutionState.WAITING_APPROVAL

// ---------------------------------------------------------------------------
// 批 B — start-mode (trigger) helpers
// ---------------------------------------------------------------------------

/**
 * Decode [MissionEntity.triggerJson] into a [TriggerSpec]. `null` for the immediate start mode,
 * for blank/malformed JSON, or for a trigger type this build no longer knows.
 */
val MissionEntity.triggerSpec: me.rerere.rikkahub.workflow.model.TriggerSpec?
    get() = runCatching {
        triggerJson?.takeIf { it.isNotBlank() }
            ?.let { JsonInstant.decodeFromString<me.rerere.rikkahub.workflow.model.TriggerSpec>(it) }
    }.getOrNull()

/**
 * True when this mission is driven by a trigger (time / event) and therefore recurs: every fire
 * starts a new round, and a done signal ends the occurrence rather than the whole mission.
 *
 * `Manual` triggers count as non-recurring — a manual-trigger mission is just the immediate mode
 * with an explicit label.
 */
val MissionEntity.isRecurring: Boolean
    get() = triggerSpec.let { it != null && it !is me.rerere.rikkahub.workflow.model.TriggerSpec.Manual }

/** True when this mission should be registered with the trigger engine right now. */
val MissionEntity.isTriggerArmed: Boolean
    get() = triggerEnabled &&
        statusEnum == MissionStatus.ACTIVE &&
        triggerSpec != null &&
        triggerSpec !is me.rerere.rikkahub.workflow.model.TriggerSpec.Manual
