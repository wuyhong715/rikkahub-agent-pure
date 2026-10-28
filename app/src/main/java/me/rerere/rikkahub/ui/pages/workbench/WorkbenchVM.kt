package me.rerere.rikkahub.ui.pages.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.executionStateEnum
import me.rerere.rikkahub.data.mission.isLive
import me.rerere.rikkahub.data.mission.statusEnum
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.subagent.SubAgentRun
import me.rerere.rikkahub.subagent.SubAgentStatus
import java.util.Calendar

class WorkbenchVM(
    private val missionRepository: MissionRepository,
    private val workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository,
    private val missionCreation: me.rerere.rikkahub.service.mission.MissionCreationService,
    /** 批 4 — 建任务的共用路径（会话内入口走的是同一个）。 */
    private val missionDraftExecutor: MissionDraftExecutor,
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

    /**
     * 批 4 — 建任务。
     *
     * 逻辑搬进了 [MissionDraftExecutor]：会话内「开始任务」和这里必须是同一条路径，否则
     * 两条路的预算 / 工作区 / 来源迟早分叉。[sourceConversationId] 非空表示这个任务是用户
     * 在某个对话里发起的（工作台从会话里打开时也会带上），任务会带摘要开跑、落定后回推消息。
     */
    fun createMission(draft: MissionDraft, sourceConversationId: String? = null) {
        viewModelScope.launch {
            val result = runCatching {
                missionDraftExecutor.execute(draft, sourceConversationId = sourceConversationId)
            }.getOrElse { error ->
                launchError.value = error.message ?: error::class.simpleName
                return@launch
            }
            result.launchError?.let { launchError.value = it }
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
