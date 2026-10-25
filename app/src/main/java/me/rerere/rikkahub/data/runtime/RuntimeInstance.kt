package me.rerere.rikkahub.data.runtime

import kotlinx.coroutines.flow.Flow

/**
 * Moxw — 运行实例（runtime instance）。
 *
 * 一个实例是"命令、文件、UI 操作真正在哪里执行"的那一端。产品上只有五类：
 * App 宿主 / Shizuku(root) / 工作区 Ubuntu / SSH 主机 / Termux。它们此前各自有一份状态枚举、
 * 一份失败文案、一个设置入口，互不可比；这里把它们收敛成同一个小模型，让设置页、体检页、
 * 工具门控和提示词读同一个真值。
 *
 * 设计约束（与本文件同批的 [InstanceStateMappers] / [InstanceRegistry] 一起看）：
 * - [InstanceState] 刻意只有四态。差异写进 `why` / `recovery` 两个**句子**，不是新增枚举值，
 *   否则每接一个后端就要改一次 UI 和提示词。
 * - 能力是向量（[Capability]）。App 宿主天然多项且互相独立；Shizuku 的 root 是 binder 之外的
 *   第二维（同一个 binder 可能只是 uid 2000）。压成单个 Ready 字符串会说谎。
 * - 本文件不依赖 Android，也不依赖任何具体后端，[InstanceStateMappers] 与
 *   [InstanceRegistry] 都能在裸 JVM 上测。
 */
enum class InstanceKind(val label: String) {
    APP_HOST("App 宿主"),
    SHIZUKU("Shizuku / root"),
    WORKSPACE("工作区 Ubuntu"),
    SSH("SSH 主机"),
    TERMUX("Termux"),
}

/**
 * kind + key 唯一。
 *
 * APP_HOST / SHIZUKU / TERMUX 是进程级单例，key 恒为 [LOCAL_KEY]；WORKSPACE 用 workspaceId，
 * SSH 用主机名（`ssh_hosts` 的主键）。
 */
data class InstanceId(val kind: InstanceKind, val key: String) {
    /** 稳定的字符串键：用于日志、偏好键、缓存。 */
    val serial: String get() = "${kind.name.lowercase()}:$key"

    companion object {
        const val LOCAL_KEY = "local"

        fun local(kind: InstanceKind): InstanceId = InstanceId(kind, LOCAL_KEY)
    }
}

/** 用户能做的"补齐前置条件"的一步。UI 拿它决定给哪个按钮。 */
enum class SetupStep {
    INSTALL_APP,
    START_SERVICE,
    GRANT_PERMISSION,
    INSTALL_ROOTFS,
    ADD_CREDENTIAL,
    ENABLE_FEATURE,
}

/**
 * 四态：就绪 / 待配置（用户做一步就能好）/ 进行中 / 不可用（做了也可能好不了，必须给原因）。
 *
 * 注意 [Unavailable] 与 [NeedsSetup] 的分界是**用户是否清楚下一步**，不是严重程度：
 * SSH 主机"尚未验证连接"属于 [Unavailable]（这不是缺东西，只是没试过）。
 */
sealed interface InstanceState {
    data object Ready : InstanceState

    data class NeedsSetup(val step: SetupStep, val why: String) : InstanceState

    data class Busy(val label: String, val progress: Float? = null) : InstanceState

    data class Unavailable(val why: String, val recovery: String? = null) : InstanceState
}

/** 一项执行能力。工具的要求按能力表达，而不是按后端表达——同一个能力可以有多个提供者。 */
enum class Capability {
    SHELL_EXEC,
    FILE_READ,
    FILE_WRITE,
    INTERACTIVE_SESSION,
    DEVICE_UI,
    PRIVILEGED_UID,
    REMOTE_NET,
}

/**
 * 一个实例的当前快照。
 *
 * [capabilities] 是"这个后端**结构性**能做什么"，与此刻是否就绪无关；
 * 是否可用请用 [supports]，它同时要求 [state] 是 [InstanceState.Ready]。
 */
data class RuntimeInstance(
    val id: InstanceId,
    val displayName: String,
    val subtitle: String,
    val state: InstanceState,
    val capabilities: Set<Capability>,
    val lastProbeAt: Long? = null,
    val lastError: String? = null,
) {
    val isReady: Boolean get() = state is InstanceState.Ready

    fun supports(capability: Capability): Boolean = isReady && capability in capabilities

    /** 给日志和调试用的单行摘要。 */
    fun describe(): String = "$displayName(${id.serial}) = ${state::class.simpleName} $subtitle"
}

/** 统一执行请求。P0 只定义契约，P2 才让五个后端都实现。 */
data class ShellRequest(
    val command: String,
    val timeoutMs: Long,
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val maxStdoutBytes: Int = 64 * 1024,
)

data class ShellResult(
    val instance: InstanceId,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val durationMs: Long = 0,
)

/** 详情页上"修一下"的一条按钮。执行走 [InstanceAdapter.invoke]。 */
data class InstanceAction(
    val id: String,
    val label: String,
    val step: SetupStep,
    val requiresConfirm: Boolean = false,
)

sealed interface ActionResult {
    data object Done : ActionResult
    data object Unsupported : ActionResult
    data class Launched(val message: String) : ActionResult
    data class Failed(val message: String) : ActionResult
}

/**
 * 一个后端的适配器。
 *
 * [observe] 必须便宜：只读本地状态（binder 死活、包是否安装、Room 里的 shellStatus），
 * 不联网、不起进程。联网/起进程的探测一律走 [probe]，由注册表做 TTL 与单飞，只允许在
 * 用户点"重新检测"时触发。
 */
interface InstanceAdapter {
    val kind: InstanceKind

    fun observe(): Flow<List<RuntimeInstance>>

    /**
     * 真实探测。[id] 为 null 表示该后端的全部实例。
     *
     * 实现必须自己兜住异常：探不到就把原因写进 [InstanceState.Unavailable]，不要抛出——
     * 设置页不该因为一次 SSH 超时而整页崩掉。
     */
    suspend fun probe(id: InstanceId? = null): List<RuntimeInstance>

    /** 该后端支撑的工具名（详情页展示用，不是门控真值）。 */
    fun backedToolNames(): Set<String> = emptySet()

    /** 当前状态下可点的修复动作。 */
    fun actions(id: InstanceId): List<InstanceAction> = emptyList()

    /** 执行 [actions] 里的一条。默认不支持，实现按能力覆盖。 */
    suspend fun invoke(id: InstanceId, actionId: String): ActionResult = ActionResult.Unsupported

    /** 状态源变了（binder 死亡、包增删、DB 变更）时让 [observe] 重新发射。 */
    fun invalidate() {}
}

/** 五个后端共用的最小执行面。App 宿主没有 shell，调用即 [ActionResult.Unsupported] 语义。 */
interface ShellExecutor {
    val instance: InstanceId

    suspend fun exec(request: ShellRequest): ShellResult
}
