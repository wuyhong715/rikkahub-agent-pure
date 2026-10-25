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
 * [probe] 先用 Room 的 shellStatus 给出观察值，再对 READY 的工作区真的进 proot 跑一次
 * `uname -sr`：状态是绿的但 proot 起不来，是这套东西最容易骗人的地方。深探测只在用户
 * 明确点「重新检测」时走到（注册表 TTL 保护），列表滚动不会拉起任何进程。
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
            .map { entity ->
                val observed = toInstance(entity, bound[entity.id] ?: 0, lastProbeAt = now)
                // 只有状态已经是 READY 才值得花一次 proot 启动去验证；DISABLED/BROKEN 的结论
                // 不需要跑命令就知道。这条深探测只在用户点「重新检测」时走到（注册表 TTL 保护）。
                if (observed.isReady) deepCheck(observed) else observed
            }
    }

    /**
     * 真的进 proot 跑一条命令。
     *
     * 只读 Room 的 shellStatus 会说谎：rootfs 装着、状态是 READY，但 proot 起不来或 `/bin/bash`
     * 缺了，工具调用会失败而设置页仍是绿的。`uname -sr` 是最便宜的证据——成功时还能顺手把
     * 内核版本写进副标题。
     */
    private suspend fun deepCheck(instance: RuntimeInstance): RuntimeInstance {
        val result = runCatching {
            repository.executeCommand(instance.id.key, "uname -sr", ".", DEEP_PROBE_TIMEOUT_MS)
        }.getOrElse { t ->
            return instance.copy(
                state = InstanceState.Unavailable(
                    why = "Linux 环境起不来：${t.message ?: "proot 启动失败"}。",
                    recovery = "在工作区详情里重装 rootfs（文件区不受影响），然后重新检测。",
                ),
                capabilities = emptySet(),
                lastError = t.message,
            )
        }

        if (result.exitCode != 0 || result.timedOut) {
            val reason = if (result.timedOut) "命令超时" else "退出码 ${result.exitCode}"
            val detail = result.stderr.trim().take(200).ifBlank { "没有 stderr" }
            return instance.copy(
                state = InstanceState.Unavailable(
                    why = "Linux 环境能启动，但命令失败（$reason）：$detail",
                    recovery = "在工作区详情里打开终端确认 /bin/bash 可用；必要时重装 rootfs。",
                ),
                capabilities = emptySet(),
            )
        }

        val kernel = result.stdout.trim().ifBlank { "Linux" }
        return instance.copy(subtitle = "$kernel · ${instance.subtitle.substringAfter(" · ", "")}")
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

    private companion object {
        /** proot 冷启动 + uname 通常 1 s 内；5 s 已经能区分"慢"和"坏了"。 */
        const val DEEP_PROBE_TIMEOUT_MS = 5_000L
    }
}
