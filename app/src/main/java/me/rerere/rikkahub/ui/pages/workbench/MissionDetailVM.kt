package me.rerere.rikkahub.ui.pages.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.mission.Artifact
import me.rerere.rikkahub.data.mission.ArtifactsRepository
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionExecutionState
import me.rerere.rikkahub.data.mission.MissionLimits
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionSnapshot
import me.rerere.rikkahub.data.mission.MissionSnapshotStore
import me.rerere.rikkahub.data.mission.MissionSettlement
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.acceptanceChecked
import me.rerere.rikkahub.data.mission.executionStateEnum
import me.rerere.rikkahub.data.mission.isLive
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.mission.LaunchSource
import me.rerere.rikkahub.service.mission.MissionForkService
import me.rerere.rikkahub.service.mission.MissionContinueComposer
import me.rerere.rikkahub.service.mission.MissionLauncher
import me.rerere.rikkahub.service.mission.MissionWorkflowDocs
import me.rerere.rikkahub.service.mission.WORKFLOW_DIR
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.uuid.Uuid

class MissionDetailVM(
    private val missionId: String,
    private val missionRepository: MissionRepository,
    private val artifactsRepository: ArtifactsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val missionLauncher: MissionLauncher,
    /** 批 F — 轮次快照（回退用）。 */
    private val snapshotStore: MissionSnapshotStore,
    /** 批 I — 任务分叉。 */
    private val forkService: MissionForkService,
) : ViewModel() {

    val mission: StateFlow<MissionEntity?> = missionRepository.observeMission(missionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val events: StateFlow<List<RunEventEntity>> = missionRepository.observeEvents(missionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Settlement card data — null until the mission row is first read. */
    val settlement: StateFlow<MissionSettlement?> = artifactsRepository.observeSettlement(missionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Artifacts produced by the mission, oldest first. */
    val artifacts: StateFlow<List<Artifact>> = artifactsRepository.observeArtifacts(missionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleAcceptance(index: Int) {
        viewModelScope.launch {
            val current = mission.value ?: return@launch
            val checked = current.acceptanceChecked.toMutableList()
            if (index in checked) {
                checked.remove(index)
            } else {
                checked.add(index)
            }
            missionRepository.setAcceptanceChecked(missionId, checked.sorted())
        }
    }

    fun transition(to: MissionStatus) {
        viewModelScope.launch {
            val m = runCatching { missionRepository.getById(missionId) }.getOrNull()
            // A transition out of ACTIVE ends the mission, not just its label. Stop the round
            // that may still be generating and park the runtime, so a later restart cannot
            // resume it as an unfinished mission.
            if (m != null && to != MissionStatus.ACTIVE && m.executionStateEnum.isLive) {
                missionRepository.logEvent(
                    missionId = missionId,
                    type = RunEventType.NOTE,
                    title = "user_stop",
                    detail = "mission -> $to",
                    runId = m.lastRunId,
                )
                // Park the posture before cancelling: the generation's terminal callback may
                // fire inside stopGeneration, and the supervisor must already see IDLE.
                missionRepository.updateExecutionState(missionId, MissionExecutionState.IDLE)
                m.conversationId
                    ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
                    ?.let { conv -> runCatching { chatService.stopGeneration(conv) } }
            }
            missionRepository.transition(missionId, to)
        }
    }

    /** 批 B — arm / pause the mission's start trigger. */
    fun setTriggerEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            missionRepository.setTriggerEnabled(id, enabled)
        }
    }

    // ------------------------------------------------------------------
    // 批 D — workflow documents (pick / create / follow-up on review)
    // ------------------------------------------------------------------

    /**
     * Lists the workspace's `workflows/ 下的 .md` as relative paths (`workflows/x.md`),
     * sorted. Empty when the mission has no resolvable workspace or the directory is
     * missing — the dialog then shows only the "model chooses" option.
     */
    suspend fun listWorkflowDocs(): List<String> = runCatching {
        val workspaceId = resolveWorkspaceId() ?: return@runCatching emptyList()
        workspaceRepository.listFiles(
            id = workspaceId,
            area = WorkspaceStorageArea.FILES,
            path = WORKFLOW_DIR,
        )
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            .map { MissionWorkflowDocs.pathOf(it.name) }
            .filter { MissionWorkflowDocs.isWorkflowDoc(it) }
            .sorted()
    }.getOrDefault(emptyList())

    /** Pin the mission to [path] (`workflows/x.md`) or back to model-chooses (`null`). */
    fun setWorkflowPath(path: String?) {
        viewModelScope.launch {
            missionRepository.updateWorkflowPath(missionId, path)
            missionRepository.logEvent(
                missionId = missionId,
                type = RunEventType.NOTE,
                title = "workflow_set",
                detail = path ?: "model chooses",
            )
        }
    }

    /**
     * Creates `workflows/<sanitized-name>.md` from the built-in template and pins the
     * mission to it. Returns the new relative path, or null when the input is unusable,
     * the mission has no workspace, or a document with that name already exists (the
     * dialog keeps the user's text so they can pick another name).
     */
    suspend fun createWorkflowDoc(rawName: String): String? = runCatching {
        val workspaceId = resolveWorkspaceId() ?: return@runCatching null
        val path = MissionWorkflowDocs.sanitizeDocName(rawName) ?: return@runCatching null
        if (path in listWorkflowDocs()) return@runCatching null
        val title = rawName.trim().removeSuffix(".md").ifBlank { "工作流程" }
        workspaceRepository.writeText(
            id = workspaceId,
            path = path,
            text = MissionWorkflowDocs.template(
                title = title,
                goal = mission.value?.description.orEmpty(),
            ),
            overwrite = false,
        )
        missionRepository.updateWorkflowPath(missionId, path)
        missionRepository.logEvent(
            missionId = missionId,
            type = RunEventType.NOTE,
            title = "workflow_created",
            detail = path,
        )
        path
    }.getOrNull()

    /**
     * 批 D — a review rejection: record the user's change request, return the mission to
     * ACTIVE and start a fresh round in the same conversation carrying the feedback.
     *
     * The resume counter is reset first: the rejection is a deliberate user action, so the
     * auto-continue breaker should give the rework its full budget rather than inheriting
     * an exhausted one. If the launch fails (no conversation yet, no assistant), the
     * mission stays ACTIVE so the user can start it manually; the toast carries the reason.
     */
    fun requestChanges(feedback: String) {
        viewModelScope.launch {
            val m = runCatching { missionRepository.getById(missionId) }.getOrNull() ?: return@launch
            val trimmed = feedback.trim()
            missionRepository.logEvent(
                missionId = missionId,
                type = RunEventType.NOTE,
                title = "review_rework",
                detail = trimmed.ifBlank { null },
                runId = m.lastRunId,
            )
            missionRepository.transition(missionId, MissionStatus.ACTIVE, note = "review rework")
            missionRepository.resetResumeCount(missionId)
            val prompt = MissionContinueComposer.composeRework(trimmed)
            val outcome = missionLauncher.launch(
                missionId = missionId,
                source = LaunchSource.Continue(round = 1),
                continuePrompt = prompt,
            )
            if (!outcome.ok) launchError.value = outcome.error
        }
    }

    /**
     * 批 B.5 — edit the circuit-breaker limits after creation. Previously they could only
     * be set in the create sheet; a mission that keeps parking itself needs to be tuned
     * from the detail page without being recreated.
     */
    fun updateLimits(limits: MissionLimits) {
        viewModelScope.launch {
            missionRepository.updateLimits(missionId, limits)
        }
    }

    fun addNote(text: String) {
        viewModelScope.launch {
            missionRepository.logEvent(
                missionId = missionId,
                type = RunEventType.NOTE,
                title = text,
            )
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching { snapshotStore.purge(missionId) }
            missionRepository.delete(missionId)
            onDeleted()
        }
    }

    // ------------------------------------------------------------------
    // 批 F — 轮次快照 / 回退
    // ------------------------------------------------------------------

    private val _snapshots = MutableStateFlow<List<MissionSnapshot>>(emptyList())

    /** 该任务的轮次快照，最新在前。 */
    val snapshots: StateFlow<List<MissionSnapshot>> = _snapshots

    /** 回退结果提示（一次性）；页面用 toast 消费后 [clearRollbackMessage]。 */
    private val _rollbackMessage = MutableStateFlow<String?>(null)
    val rollbackMessage: StateFlow<String?> = _rollbackMessage

    fun clearRollbackMessage() {
        _rollbackMessage.value = null
    }

    /** 重新读一遍快照索引（打开对话框时调）。 */
    fun refreshSnapshots() {
        viewModelScope.launch {
            _snapshots.value = runCatching { snapshotStore.list(missionId) }.getOrDefault(emptyList())
        }
    }

    /**
     * 把工作区回退到 [snapshot] 拍下的状态。
     *
     * 危险操作，所以只从「二次确认过的」UI 入口调。恢复前的当前状态会自动存成一张 `pre_restore`
     * 快照（见 [MissionSnapshotStore.restore]），所以回退本身也能撤销。
     */
    fun rollbackTo(snapshot: MissionSnapshot) {
        viewModelScope.launch {
            val mission = runCatching { missionRepository.getById(missionId) }.getOrNull()
            // 轮次正在跑的时候换文件等于偷走它脚下的地板：先让用户停轮次。
            if (mission != null && mission.executionStateEnum.isLive) {
                _rollbackMessage.value = "任务正在执行，先停止当前轮次再回退"
                return@launch
            }
            val workspaceId = resolveWorkspaceId()
            if (workspaceId == null) {
                _rollbackMessage.value = "任务没有绑定工作区，无法回退"
                return@launch
            }
            val dir = runCatching { workspaceRepository.filesDir(workspaceId) }.getOrNull()
            if (dir == null) {
                _rollbackMessage.value = "工作区目录不存在，无法回退"
                return@launch
            }
            when (val result = snapshotStore.restore(dir, snapshot)) {
                is MissionSnapshotStore.RestoreResult.Restored -> {
                    val safety = result.safetySnapshotId?.let { "（回退前状态已存为 $it）" }.orEmpty()
                    missionRepository.logEvent(
                        missionId = missionId,
                        type = RunEventType.NOTE,
                        title = "rollback",
                        detail = "回退到第 ${snapshot.round} 轮开始前 · ${result.fileCount} 个文件$safety",
                    )
                    _rollbackMessage.value = "已回退到第 ${snapshot.round} 轮开始前$safety"
                    refreshSnapshots()
                }

                is MissionSnapshotStore.RestoreResult.Failed -> {
                    _rollbackMessage.value = result.reason
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Batch A — run controls (launch / stop / relaunch)
    // ------------------------------------------------------------------

    /** One-shot launch failure message; consumed by the page via a toast, then cleared. */
    val launchError = MutableStateFlow<String?>(null)

    fun clearLaunchError() {
        launchError.value = null
    }

    /** User-initiated stop: halt generation on the bound conversation and park the runtime. */
    fun stopRun() {
        viewModelScope.launch {
            val m = mission.value ?: return@launch
            val convId = m.conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: return@launch
            // Journal the stop BEFORE cancelling the generation: the round then ends as a
            // normal COMPLETED run, and the supervisor must be able to see this NOTE on the
            // run's own journal (hence the runId) or it will happily "continue" the stop.
            missionRepository.logEvent(
                missionId = missionId,
                type = RunEventType.NOTE,
                title = "user_stop",
                runId = m.lastRunId,
            )
            // Park the posture before cancelling: the generation's terminal callback may fire
            // inside stopGeneration, and the supervisor must already see IDLE.
            missionRepository.updateExecutionState(missionId, MissionExecutionState.IDLE)
            runCatching { chatService.stopGeneration(convId) }
        }
    }

    /**
     * 批 I — 分叉：把当前产物复制到一个新工作区，另起一个任务走另一条路。
     *
     * 结果用 [forkOutcome] 暴露给页面（消息 + 新任务 id），页面负责提示；这里不导航，
     * 免得 VM 依赖导航栈。
     */
    val forkOutcome = MutableStateFlow<ForkOutcome?>(null)

    data class ForkOutcome(val missionId: String, val message: String)

    fun fork(instruction: String) {
        viewModelScope.launch {
            val outcome = runCatching { forkService.fork(missionId, instruction) }
                .getOrElse { error ->
                    forkOutcome.value = ForkOutcome(
                        missionId = "",
                        message = "分叉失败：${error.message ?: "未知原因"}",
                    )
                    return@launch
                }
            forkOutcome.value = ForkOutcome(
                missionId = outcome.missionId,
                message = if (outcome.started) {
                    "已分叉，新任务已启动（${outcome.fileCount} 个文件）"
                } else {
                    "已分叉为新任务，但第一轮没起来：${outcome.launchError ?: "请手动启动"}"
                },
            )
        }
    }

    fun clearForkOutcome() {
        forkOutcome.value = null
    }

    /** First / manual launch of the mission (wish-style Create source). */
    fun launchMission() {
        viewModelScope.launch {
            val o = missionLauncher.launch(missionId, LaunchSource.Create)
            if (!o.ok) launchError.value = o.error
        }
    }

    /** Restart a blocked mission: reset the auto-continue budget, re-activate, launch fresh. */
    fun relaunch() {
        viewModelScope.launch {
            missionRepository.resetResumeCount(missionId)
            missionRepository.transition(missionId, MissionStatus.ACTIVE)
            val o = missionLauncher.launch(missionId, LaunchSource.Create)
            if (!o.ok) launchError.value = o.error
        }
    }

    // ------------------------------------------------------------------
    // Batch 1 — artifact open / share (delivery loop)
    // ------------------------------------------------------------------

    /**
     * Load an artifact file's text through the mission's bound workspace, for the
     * in-app preview dialog. Best-effort: null when the assistant / workspace / file
     * cannot be resolved (deleted workspace, oversized file, unreadable content).
     */
    suspend fun loadArtifactText(path: String): String? = try {
        val workspaceId = resolveWorkspaceId() ?: return null
        val size = workspaceRepository.rootfsFileSize(workspaceId, path)
        if (size > MAX_ARTIFACT_READ_BYTES) return null
        ByteArrayOutputStream().use { out ->
            workspaceRepository.exportRootfsFile(workspaceId, path, out)
            out.toString(Charsets.UTF_8.name())
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Export an artifact into [cacheDir] so the page can hand the file to a
     * FileProvider share intent. Best-effort; null when the file cannot be read.
     */
    suspend fun exportArtifactToCache(path: String, cacheDir: File): File? = try {
        val workspaceId = resolveWorkspaceId() ?: return null
        val dir = File(cacheDir, "artifact_share").apply { mkdirs() }
        val file = File(dir, path.substringAfterLast('/').ifBlank { "artifact" })
        file.outputStream().use { out ->
            workspaceRepository.exportRootfsFile(workspaceId, path, out)
        }
        file
    } catch (e: Exception) {
        null
    }

    /** Resolve the workspace bound to the mission, if any. */
    private suspend fun resolveWorkspaceId(): String? {
        // The mission's own binding wins; fall back to its assistant's workspace.
        mission.value?.workspaceId?.takeIf { it.isNotBlank() }?.let { return it }
        val assistantId = mission.value?.assistantId ?: return null
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.assistants.firstOrNull { it.id.toString() == assistantId } ?: return null
        return assistant.workspaceId?.toString()
    }

    companion object {
        /** Preview / share cap — 2 MB, matching the workspace preview bound. */
        private const val MAX_ARTIFACT_READ_BYTES = 2L * 1024 * 1024
    }
}
