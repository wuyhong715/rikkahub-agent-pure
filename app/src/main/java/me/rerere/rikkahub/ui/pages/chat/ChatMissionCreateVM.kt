package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.mission.MissionCreationService
import me.rerere.rikkahub.ui.pages.workbench.MissionDraft
import me.rerere.rikkahub.ui.pages.workbench.MissionDraftExecutor

/**
 * 批 4（M102 反馈④）— 在**当前对话**里创建任务的那点状态。
 *
 * 刻意不跳工作台：用户正在聊某件事，把「开始任务」做成一次页面跳转，等于让他丢下上下文去
 * 另一个界面重新填一遍表。所以表单就地弹出、目标预填，提交完回到底下的对话——任务开始后
 * 会由 MissionConversationEcho 往这里投一条消息。
 *
 * 真正的建任务逻辑在 [MissionDraftExecutor]（与工作台共用），这里只管「表开没开 / 预填什么 /
 * 报什么错」。
 */
class ChatMissionCreateVM(
    private val executor: MissionDraftExecutor,
    private val missionCreation: MissionCreationService,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {

    /** 一次「开始任务」的入参：来源对话 + 预填的目标（草稿或某条消息的正文）。 */
    data class Seed(
        val sourceConversationId: String,
        val goal: String,
    )

    private val _seed = MutableStateFlow<Seed?>(null)

    /** 非空 = 创建表开着。 */
    val seed: StateFlow<Seed?> = _seed.asStateFlow()

    private val _launchError = MutableStateFlow<String?>(null)

    /** 任务建好了但第一轮没跑起来（没有助手、会话写不进去…）。 */
    val launchError: StateFlow<String?> = _launchError.asStateFlow()

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository.listFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 打开创建表。[goal] 为空（输入框没内容）时只开表，不预填。
     */
    fun open(sourceConversationId: Uuid, goal: String) {
        _seed.value = Seed(
            sourceConversationId = sourceConversationId.toString(),
            goal = goal.trim(),
        )
    }

    fun dismiss() {
        _seed.value = null
    }

    fun clearLaunchError() {
        _launchError.value = null
    }

    /** 创建前把默认工作区的 `workflows/` 铺好，否则表里的 playbook 选择器是空的。 */
    fun prepareWorkflowLibrary() {
        viewModelScope.launch {
            runCatching {
                val workspace = missionCreation.defaultWorkspace()
                missionCreation.seedWorkflows(workspace.id)
            }
        }
    }

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

    fun create(draft: MissionDraft) {
        val source = _seed.value?.sourceConversationId
        viewModelScope.launch {
            val result = runCatching { executor.execute(draft, sourceConversationId = source) }
                .getOrElse { error ->
                    _launchError.value = error.message ?: error::class.simpleName
                    return@launch
                }
            result.launchError?.let { _launchError.value = it }
        }
    }
}
