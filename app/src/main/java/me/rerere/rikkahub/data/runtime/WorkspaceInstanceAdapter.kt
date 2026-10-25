package me.rerere.rikkahub.data.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository

/**
 * 工作区 Ubuntu：proot + rootfs，每行一个实例，key 是 workspaceId。
 *
 * 它比其他四个多一层关系：**助手绑定**。`assistant.workspaceId` 决定哪些对话能拿到
 * `workspace_*` 工具，所以详情页必须能回答"这个工作区被几个助手用着"——以前只能去助手设置里翻。
 *
 * [probe] 目前只读 Room 里的 shellStatus，不拉起 proot：`uname` 那种深探测会起进程、慢，
 * 留到用户在详情页明确点「重新检测」时再做（P2 接 [ShellExecutor] 时一并实现）。
 */
class WorkspaceInstanceAdapter(
    private val repository: WorkspaceRepository,
    private val settingsStore: SettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : InstanceAdapter {

    override val kind: InstanceKind = InstanceKind.WORKSPACE

    override fun observe(): Flow<List<RuntimeInstance>> =
        combine(repository.listFlow(), settingsStore.settingsFlow) { workspaces, settings ->
            val bound = settings.assistants
                .mapNotNull { it.workspaceId?.toString() }
                .groupingBy { it }
                .eachCount()
            workspaces.map { entity -> toInstance(entity, bound[entity.id] ?: 0, lastProbeAt = null) }
        }

    override suspend fun probe(id: InstanceId?): List<RuntimeInstance> {
        val workspaces = repository.listFlow().first()
        val settings = settingsStore.settingsFlow.first()
        val bound = settings.assistants
            .mapNotNull { it.workspaceId?.toString() }
            .groupingBy { it }
            .eachCount()
        val now = clock()
        return workspaces
            .filter { id == null || it.id == id.key }
            .map { entity -> toInstance(entity, bound[entity.id] ?: 0, lastProbeAt = now) }
    }

    override fun backedToolNames(): Set<String> = setOf(
        "workspace_read_file",
        "workspace_write_file",
        "workspace_edit_file",
        "workspace_create_folder",
        "workspace_read_folder",
        "workspace_shell",
        "workspace_run_background",
        "workspace_background_status",
        "workspace_background_kill",
    )

    private fun toInstance(
        entity: WorkspaceEntity,
        boundAssistants: Int,
        lastProbeAt: Long?,
    ): RuntimeInstance {
        val state = InstanceStateMappers.workspace(entity.shellStatus)
        val capabilities = if (state is InstanceState.Ready) {
            setOf(Capability.SHELL_EXEC)
        } else {
            emptySet()
        }
        val binding = if (boundAssistants > 0) "被 $boundAssistants 个助手绑定" else "未绑定助手"
        return RuntimeInstance(
            id = InstanceId(InstanceKind.WORKSPACE, entity.id),
            displayName = entity.name,
            subtitle = "${entity.root} · $binding",
            state = state,
            capabilities = capabilities,
            lastProbeAt = lastProbeAt,
        )
    }
}
