package me.rerere.rikkahub.service.mission

import android.content.Context
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionExecutionState
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.isTriggerArmed
import me.rerere.rikkahub.data.mission.triggerSpec
import me.rerere.rikkahub.data.runevent.RunEventRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.trigger.TimeCronSchedule
import me.rerere.rikkahub.workflow.trigger.TimeCronTriggerFamily
import me.rerere.rikkahub.workflow.trigger.TriggerBinding
import me.rerere.rikkahub.workflow.trigger.TriggerFireCallback

/**
 * 批 B — the mission scheduler (开始方式：定时 / 触发).
 *
 * Batch A gave missions an execution runtime (launch → supervise → complete). Batch B adds a
 * *start* dimension: a mission may carry a [TriggerSpec], making it recurring. This class is
 * the bridge between that spec and the existing trigger plumbing:
 *
 * - it observes the mission table and keeps the trigger families in sync with the **armed**
 *   set (ACTIVE + trigger enabled + a non-manual spec);
 * - every family fire funnels through [onFire], which runs the atomic
 *   [MissionRepository.consumeTriggerFire] gate (status / busy / daily cap / cooldown) before
 *   launching a round through [MissionLauncher];
 * - time/cron fires coming back from WorkManager are routed in via [fireFromTimeCronWorker].
 *
 * Recurring semantics: a fire starts an *occurrence*. When the model reports done, the
 * supervisor parks the mission back to IDLE (see `MissionSupervisor.complete`) and keeps it
 * ACTIVE, so the next trigger can start a fresh round. A blocked mission stops being armed
 * until the user reopens it.
 *
 * Everything is best-effort: a bad receiver registration must never crash app start, and a
 * missed fire is preferable to a loop that throws.
 */
class MissionScheduler(
    context: Context,
    private val appScope: CoroutineScope,
    private val missionRepository: MissionRepository,
    private val launcher: MissionLauncher,
    private val runEventRepository: RunEventRepository,
    /** Notification sink (DI wires NotificationUtil; tests may pass a recorder). */
    private val notify: (title: String, content: String) -> Unit,
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) {

    private val fireCallback = TriggerFireCallback { missionId, spec -> onFire(missionId, spec) }

    private val engine = MissionTriggerEngine(
        context = context,
        scope = appScope,
        resolver = { id -> loadBinding(id) },
        listBindings = { loadBindings() },
        callback = fireCallback,
    )

    @Volatile private var started = false
    private var observeJob: Job? = null

    /** Last armed set handed to [MissionTriggerEngine.sync]; guards against redundant resyncs. */
    private var lastBindings: List<TriggerBinding> = emptyList()

    /** missionId → last `next_run_at` this process wrote (null = "no scheduled next run"). */
    private val writtenNextRunAt = HashMap<String, Long?>()

    /** Start observing missions and reconcile the trigger families. Idempotent. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        MissionTriggerDispatcher.bind(this)
        observeJob = appScope.launch(Dispatchers.IO) {
            missionRepository.observeAll()
                .debounce(RESYNC_DEBOUNCE_MS)
                .onEach { missions ->
                    val bindings = missionBindings(missions)
                    // Writing next_run_at re-emits this same flow; only resync the families
                    // when the armed set actually changed.
                    if (bindings != lastBindings) {
                        lastBindings = bindings
                        runCatching { engine.sync(bindings) }
                    }
                    runCatching { refreshNextRunAt(missions) }
                }
                .catch { Log.w(TAG, "mission scheduler observation failed", it) }
                .collect { /* drained via onEach */ }
        }
    }

    /**
     * One accepted trigger fire: gate → journal → launch a new occurrence.
     *
     * The gate is atomic in the repository, so two families firing at the same instant cannot
     * both start a round, exceed the daily cap, or slip inside the cooldown window.
     */
    suspend fun onFire(missionId: String, spec: TriggerSpec) {
        runCatching {
            val nowMs = System.currentTimeMillis()
            val day = LocalDate.now(zoneId()).toString()
            val gate = missionRepository.consumeTriggerFire(missionId, nowMs, day)
            if (gate != TriggerFireGate.FIRED) {
                journal(missionId, "trigger_skip", "${spec.javaClass.simpleName}: $gate")
                return
            }

            val label = describe(spec)
            journal(missionId, "trigger", "fired: $label")
            val outcome = launcher.launch(missionId, LaunchSource.Trigger(label))
            if (!outcome.ok) {
                runCatching {
                    missionRepository.updateExecutionState(missionId, MissionExecutionState.IDLE)
                }
                journal(missionId, "trigger_launch_failed", outcome.error ?: "unknown")
                notify("任务启动失败", outcome.error ?: "unknown")
            }
        }.onFailure {
            Log.w(TAG, "onFire failed for mission=$missionId", it)
        }
    }

    /** Route a WorkManager time/cron wake-up into the time family. */
    suspend fun fireFromTimeCronWorker(missionId: String) {
        runCatching { engine.onTimeCronFired(missionId) }
            .onFailure { Log.w(TAG, "time-cron fire failed for mission=$missionId", it) }
    }

    suspend fun shutdown() {
        runCatching { observeJob?.cancel() }
        runCatching { engine.shutdown() }
        started = false
    }

    // ---------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------

    /**
     * The armed set to hand the trigger families. The stamp is 0: the time family already
     * diffs the spec itself, and mission.updatedAt changes on every execution-state write —
     * using it would reschedule the WorkManager job after every run.
     */
    private fun missionBindings(missions: List<MissionEntity>): List<TriggerBinding> =
        missions.filter { it.isTriggerArmed }.mapNotNull { mission ->
            mission.triggerSpec?.let { spec ->
                TriggerBinding(mission.id, spec, 0L, mission.nextRunAt)
            }
        }

    /**
     * Keep `missions.next_run_at` honest for the detail page's trigger card.
     *
     * Only time/cron missions have a predictable "next": event-driven triggers (Wi-Fi,
     * charging, geofence …) have none, so their column is cleared. Writes are deduped by
     * [writtenNextRunAt] because the update itself re-emits [MissionRepository.observeAll];
     * without the guard every write would trigger another write. A task that is paused
     * (`triggerEnabled = false`) or no longer ACTIVE is cleared as well — the card must not
     * claim a next run that will never happen.
     */
    private suspend fun refreshNextRunAt(missions: List<MissionEntity>) {
        val nowMs = System.currentTimeMillis()
        val liveIds = HashSet<String>(missions.size)
        for (mission in missions) {
            liveIds.add(mission.id)
            val next = runCatching { nextRunAtFor(mission, nowMs) }.getOrNull()
            val unchanged = writtenNextRunAt.containsKey(mission.id) &&
                writtenNextRunAt[mission.id] == next &&
                mission.nextRunAt == next
            if (unchanged) continue
            writtenNextRunAt[mission.id] = next
            runCatching { missionRepository.updateNextRunAt(mission.id, next) }
        }
        writtenNextRunAt.keys.retainAll(liveIds)
    }

    /** The next scheduled fire in epoch ms, or null for missions without a clock. */
    private fun nextRunAtFor(mission: MissionEntity, nowMs: Long): Long? {
        if (!mission.isTriggerArmed) return null
        val spec = mission.triggerSpec ?: return null
        return when (spec) {
            is TriggerSpec.TimeCron -> TimeCronSchedule.nextFireMs(
                spec = spec,
                zone = zoneId(),
                nowMs = nowMs,
                // Anchoring on what already happened is what keeps this value identical across
                // the reconciles of one idle minute: refreshNextRunAt writes it into the row it
                // is reading, and a value that moved on every read turned that write into a
                // self-sustaining 3 Hz loop.
                lastFiredAtMs = mission.lastFiredAt,
                scheduledNextMs = mission.nextRunAt,
            )
            else -> null
        }
    }

    private suspend fun loadBinding(missionId: String): TriggerBinding? = runCatching {
        val mission = missionRepository.getById(missionId) ?: return@runCatching null
        if (!mission.isTriggerArmed) return@runCatching null
        mission.triggerSpec?.let { TriggerBinding(mission.id, it, 0L) }
    }.getOrNull()

    private suspend fun loadBindings(): List<TriggerBinding> = runCatching {
        missionRepository.observeAll().first()
            .filter { it.isTriggerArmed }
            .mapNotNull { mission ->
                mission.triggerSpec?.let { TriggerBinding(mission.id, it, 0L) }
            }
    }.getOrDefault(emptyList())

    private suspend fun journal(missionId: String, title: String, detail: String) {
        runCatching {
            runEventRepository.emit(
                type = RunEventType.NOTE,
                title = title,
                detail = detail,
                missionId = missionId,
            )
        }
    }

    /** Human label used in journals and the kickoff prompt. */
    private fun describe(spec: TriggerSpec): String = when (spec) {
        is TriggerSpec.TimeCron -> "定时 ${spec.timeOfDay ?: spec.cron ?: ""}".trim()
        is TriggerSpec.WifiConnected -> "Wi-Fi 连接"
        is TriggerSpec.WifiDisconnected -> "Wi-Fi 断开"
        is TriggerSpec.BluetoothDeviceConnected -> "蓝牙连接"
        is TriggerSpec.BluetoothDeviceDisconnected -> "蓝牙断开"
        is TriggerSpec.HeadphonesPlugged -> "耳机插入"
        is TriggerSpec.HeadphonesUnplugged -> "耳机拔出"
        is TriggerSpec.PowerConnected -> "充电连接"
        is TriggerSpec.PowerDisconnected -> "充电断开"
        is TriggerSpec.BatteryBelow -> "电量低于 ${spec.thresholdPercent}%"
        is TriggerSpec.BatteryAbove -> "电量高于 ${spec.thresholdPercent}%"
        is TriggerSpec.GeofenceEnter -> "进入地点"
        is TriggerSpec.GeofenceExit -> "离开地点"
        is TriggerSpec.AppLaunched -> "打开 ${spec.packageName}"
        is TriggerSpec.AppClosed -> "关闭 ${spec.packageName}"
        is TriggerSpec.NotificationReceived -> "收到通知"
        is TriggerSpec.BootCompleted -> "开机完成"
        is TriggerSpec.ScreenOn -> "屏幕点亮"
        is TriggerSpec.ScreenOff -> "屏幕熄灭"
        TriggerSpec.Manual -> "手动"
    }

    companion object {
        private const val TAG = "MissionScheduler"
        private const val RESYNC_DEBOUNCE_MS = 300L
    }
}

/**
 * 批 B — process-wide handle the WorkManager time/cron worker uses to reach the scheduler.
 *
 * The worker is constructed by WorkManager (not Koin), and pulling [MissionScheduler] out of
 * Koin would create a dependency cycle through the trigger engine. A tiny bound dispatcher
 * keeps the worker dumb and the scheduler the only stateful owner.
 */
object MissionTriggerDispatcher {

    @Volatile private var scheduler: MissionScheduler? = null

    internal fun bind(value: MissionScheduler) {
        scheduler = value
    }

    /** Called from `WorkflowTimeCronWorker` (name kept for backwards compatibility). */
    suspend fun onTimeCronFired(missionId: String) {
        scheduler?.fireFromTimeCronWorker(missionId)
    }
}