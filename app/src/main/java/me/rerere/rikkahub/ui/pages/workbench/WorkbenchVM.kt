package me.rerere.rikkahub.ui.pages.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionLimits
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.executionStateEnum
import me.rerere.rikkahub.data.mission.isLive
import me.rerere.rikkahub.data.mission.statusEnum
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.runtime.util.JsonInstant
import me.rerere.rikkahub.service.mission.LaunchSource
import me.rerere.rikkahub.service.mission.MissionLauncher
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.subagent.SubAgentRun
import me.rerere.rikkahub.subagent.SubAgentStatus
import me.rerere.rikkahub.workflow.model.TriggerSpec
import java.util.Calendar

class WorkbenchVM(
    private val missionRepository: MissionRepository,
    private val missionLauncher: MissionLauncher,
    private val workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository,
    private val missionCreation: me.rerere.rikkahub.service.mission.MissionCreationService,
    /** Deleted tasks drop their round snapshots with them — see [delete]. */
    private val snapshotStore: me.rerere.rikkahub.data.mission.MissionSnapshotStore,
    /** 从列表直接暂停/完成/删除时，正在跑的那一轮必须一起停下来。 */
    private val chatService: me.rerere.rikkahub.service.ChatService,
    subAgentRegistry: SubAgentRegistry,
) : ViewModel() {

    val missions: StateFlow<List<MissionEntity>> = missionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeSubAgents: StateFlow<List<SubAgentRun>> = subAgentRegistry.runs
        .map { runs ->
            runs.values
                .filter { it.status == SubAgentStatus.PENDING || it.status == SubAgentStatus.RUNNING }
                .sortedBy { it.startedAtMs }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeMissions: StateFlow<List<MissionEntity>> = missions
        .map { list ->
            list.filter {
                it.statusEnum == MissionStatus.ACTIVE || it.statusEnum == MissionStatus.BLOCKED
            }.sortedByDescending { it.updatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reviewMissions: StateFlow<List<MissionEntity>> = missions
        .map { list ->
            list.filter { it.statusEnum == MissionStatus.REVIEW }
                .sortedByDescending { it.updatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentDoneMissions: StateFlow<List<MissionEntity>> = missions
        .map { list ->
            list.filter { it.statusEnum == MissionStatus.DONE }
                .sortedByDescending { it.completedAt ?: it.updatedAt }
                .take(3)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val doneToday: StateFlow<Int> = missions
        .map { list ->
            list.count { mission ->
                mission.statusEnum == MissionStatus.DONE &&
                    mission.completedAt?.let { ts -> isToday(ts) } == true
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val launchError = MutableStateFlow<String?>(null)

    fun clearLaunchError() {
        launchError.value = null
    }

    /**
     * Creates and seeds the default mission workspace before the create sheet opens, so the
     * workflow picker has something to show on a brand-new install instead of only offering
     * "let the model choose".
     */
    fun prepareWorkflowLibrary() {
        viewModelScope.launch {
            runCatching {
                val workspace = missionCreation.defaultWorkspace()
                missionCreation.seedWorkflows(workspace.id)
            }
        }
    }

    /**
     * 批 C — `workflows/ 下的 .md` of a workspace, for the create sheet's playbook picker.
     * Best-effort: an unreadable workspace yields an empty list (the sheet then shows
     * 「由模型自选」 only).
     */
    suspend fun listWorkflowDocs(workspaceId: String): List<String> = runCatching {
        workspaceRepository.listFiles(
            id = workspaceId,
            area = me.rerere.workspace.WorkspaceStorageArea.FILES,
            path = me.rerere.rikkahub.service.mission.WORKFLOW_DIR,
        )
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            .map { it.name }
            .sorted()
    }.getOrDefault(emptyList())

    fun createMission(draft: MissionDraft) {
        viewModelScope.launch {
            // 批 B — trigger start mode: serialize the spec; null = the immediate wish-style path.
            val triggerJson = draft.trigger?.let { spec ->
                runCatching { JsonInstant.encodeToString<TriggerSpec>(spec) }.getOrNull()
            }
            // Default workspace + workflow seeding: a mission created without an explicit
            // workspace used to have no `workflows/` folder at all, so the picker and the
            // model's chooser were always empty. The creation service owns that guarantee.
            val workspaceId = draft.workspaceId ?: runCatching {
                missionCreation.defaultWorkspace().id
            }.getOrNull()
            if (workspaceId != null) {
                runCatching { missionCreation.seedWorkflows(workspaceId) }
            }
            val mission = missionRepository.create(
                title = draft.title,
                description = draft.description,
                acceptance = draft.goals,
                conversationId = null,
                triggerJson = triggerJson,
                cooldownSeconds = if (triggerJson != null) draft.cooldownSeconds else 0,
                maxRunsPerDay = if (triggerJson != null) draft.maxRunsPerDay else null,
                workflowPath = draft.workflowPath,
            )
            missionRepository.updateWorkspaceAndAssistant(mission.id, workspaceId, draft.assistantId)
            draft.approvalMode?.let { missionRepository.updateApprovalMode(mission.id, it) }
            // 批 4 — 预算档位：draft 里没填的字段一律取 MissionLimits 的默认值（标准档），
            // 不再在这里抄一份 5 / 2 / 500_000 的魔数（那样改默认值等于改两处，迟早不同步）。
            if (draft.maxResumeRounds != null || draft.stallRounds != null || draft.tokenBudget != null ||
                draft.roundTimeoutMinutes != null || draft.wrapUpMinutes != null ||
                draft.requireConfirmation
            ) {
                val defaults = MissionLimits()
                missionRepository.updateLimits(
                    mission.id,
                    defaults.copy(
                        maxResumeRounds = draft.maxResumeRounds ?: defaults.maxResumeRounds,
                        stallRounds = draft.stallRounds ?: defaults.stallRounds,
                        tokenBudget = draft.tokenBudget ?: defaults.tokenBudget,
                        requireConfirmation = draft.requireConfirmation,
                        roundTimeoutMinutes = draft.roundTimeoutMinutes ?: defaults.roundTimeoutMinutes,
                        wrapUpMinutes = draft.wrapUpMinutes ?: defaults.wrapUpMinutes,
                    ),
                )
            }
            if (triggerJson == null) {
                val outcome = missionLauncher.launch(mission.id, LaunchSource.Create)
                if (!outcome.ok) {
                    launchError.value = outcome.error
                }
            } else {
                // Trigger-driven: MissionScheduler picks the mission up; do not launch now.
                runCatching {
                    missionRepository.logEvent(
                        mission.id,
                        RunEventType.NOTE,
                        title = "trigger_armed",
                        detail = draft.trigger?.let { it::class.simpleName },
                    )
                }
            }
        }
    }

    /** 停掉这一轮生成并把姿态落回 IDLE；任务不在跑时是空操作。 */
    private suspend fun stopLiveRound(id: String) {
        val mission = runCatching { missionRepository.getById(id) }.getOrNull() ?: return
        if (!mission.executionStateEnum.isLive) return
        mission.conversationId
            ?.let { raw -> runCatching { kotlin.uuid.Uuid.parse(raw) }.getOrNull() }
            ?.let { conversationId -> runCatching { chatService.stopGeneration(conversationId) } }
        missionRepository.updateExecutionState(
            id,
            me.rerere.rikkahub.data.mission.MissionExecutionState.IDLE,
        )
    }

    fun transition(id: String, to: MissionStatus) {
        viewModelScope.launch {
            // 列表上的暂停/完成/归档以前只改任务状态行：正在跑的那一轮不会收到任何通知，
            // execution_state 留在 RUNNING，前台服务常驻、定时触发永远 SKIPPED_BUSY。
            if (to != MissionStatus.ACTIVE) runCatching { stopLiveRound(id) }
            missionRepository.transition(id, to)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { stopLiveRound(id) }
            // The detail page already purges before deleting; this list path did not, so every
            // task deleted from the workbench left up to 12 × 128 MB of round snapshots on disk
            // forever. Deleting the row must not be the only thing that happens.
            runCatching { snapshotStore.purge(id) }
            missionRepository.delete(id)
        }
    }
}

private fun isToday(timestamp: Long): Boolean {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
}
