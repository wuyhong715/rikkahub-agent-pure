package me.rerere.rikkahub.data.runtime

import me.rerere.rikkahub.shizuku.ShizukuStatus
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Termux 的探测结论。
 *
 * 刻意在 runtime 包里另立枚举，而不是复用 `TermuxIntegration.VerifyResult`：后者定义在
 * `TermuxTool.kt` 里，那个文件拖着一整套 Android intent/broadcast 依赖。适配器负责翻译，
 * 这一层与 [InstanceStateMappers] 因此保持纯 Kotlin，可以在裸 JVM 上测。
 */
enum class TermuxProbeResult {
    NOT_INSTALLED,
    NO_PERMISSION,
    /** 装了、权限也有，但 Termux 拒绝了外部调用：allow-external-apps 没开，或 Termux 在后台。 */
    ALLOW_EXTERNAL_APPS_MISSING,
    OK,
    FAILED,
}

/**
 * 后端状态枚举 → [InstanceState] 的唯一映射处。
 *
 * 每个后端只在这里翻译一次；适配器不写文案，UI 也只认四态。文案是给人和模型看的成品句子，
 * 所以放在映射里而不是散在 Composable 里。
 */
object InstanceStateMappers {

    fun shizuku(status: ShizukuStatus): InstanceState = when (status) {
        ShizukuStatus.NOT_INSTALLED -> InstanceState.NeedsSetup(
            step = SetupStep.INSTALL_APP,
            why = "没有检测到 Shizuku（或 Sui）：当前没有任何提权通道。",
        )

        ShizukuStatus.NOT_RUNNING -> InstanceState.NeedsSetup(
            step = SetupStep.START_SERVICE,
            why = "Shizuku 已安装，但它的服务没有运行（没有可用 binder）。",
        )

        ShizukuStatus.PERMISSION_DENIED -> InstanceState.NeedsSetup(
            step = SetupStep.GRANT_PERMISSION,
            why = "Shizuku 服务在运行，但本应用还没有获得授权。",
        )

        ShizukuStatus.READY -> InstanceState.Ready
    }

    /**
     * Shizuku 的能力是两维的：binder 通了只说明 uid 2000（adb shell 同级），
     * root 还得看 broker 自己是不是 uid 0。
     */
    fun shizukuCapabilities(brokerUid: Int?): Set<Capability> =
        if (brokerUid == 0) {
            setOf(Capability.SHELL_EXEC, Capability.PRIVILEGED_UID)
        } else {
            setOf(Capability.SHELL_EXEC)
        }

    fun workspace(status: WorkspaceShellStatus): InstanceState = when (status) {
        WorkspaceShellStatus.READY -> InstanceState.Ready

        WorkspaceShellStatus.DISABLED -> InstanceState.NeedsSetup(
            step = SetupStep.INSTALL_ROOTFS,
            why = "这个工作区还没有安装 Linux 环境（只有文件区可用）。",
        )

        WorkspaceShellStatus.INSTALLING -> InstanceState.Busy(
            label = "正在安装 Linux 环境",
            progress = null,
        )

        WorkspaceShellStatus.BROKEN -> InstanceState.Unavailable(
            why = "Linux 环境损坏：rootfs 或工作目录不完整。",
            recovery = "在详情页点「重新检测」，必要时重装 rootfs（文件区不受影响）。",
        )
    }

    /** DB 里存的是枚举名；读到不认识的值按损坏处理，而不是崩或者假装可用。 */
    fun workspace(statusName: String): InstanceState {
        val parsed = runCatching { WorkspaceShellStatus.valueOf(statusName) }.getOrNull()
        return if (parsed == null) {
            InstanceState.Unavailable(
                why = "无法识别的环境状态：$statusName",
                recovery = "重新检测；仍然失败就重装 rootfs。",
            )
        } else {
            workspace(parsed)
        }
    }

    fun termux(probe: TermuxProbeResult?): InstanceState = when (probe) {
        null -> InstanceState.Unavailable(
            why = "还没有验证过 Termux 集成。",
            recovery = "点「重新检测」跑一次 echo 烟测。",
        )

        TermuxProbeResult.NOT_INSTALLED -> InstanceState.NeedsSetup(
            step = SetupStep.INSTALL_APP,
            why = "没有安装 Termux。",
        )

        TermuxProbeResult.NO_PERMISSION -> InstanceState.NeedsSetup(
            step = SetupStep.GRANT_PERMISSION,
            why = "Termux 已安装，但本应用没有 RUN_COMMAND 权限。",
        )

        TermuxProbeResult.ALLOW_EXTERNAL_APPS_MISSING -> InstanceState.NeedsSetup(
            step = SetupStep.ENABLE_FEATURE,
            why = "Termux 拒绝了外部调用：allow-external-apps 没开启，或 Termux 处于后台。",
        )

        TermuxProbeResult.OK -> InstanceState.Ready

        TermuxProbeResult.FAILED -> InstanceState.Unavailable(
            why = "Termux 烟测失败。",
            recovery = "把 Termux 切到前台后重新检测；仍失败就看 Termux 的通知里有没有报错。",
        )
    }

    /**
     * SSH 主机：没有凭据是"缺东西"，有凭据但没验证过是"没试过"，验证失败才是"坏了"。
     * 这三者在 UI 上是三种颜色，所以不能合并成同一句话。
     */
    fun ssh(hasCredential: Boolean, lastVerifiedAtMs: Long?, lastError: String?): InstanceState = when {
        !hasCredential -> InstanceState.NeedsSetup(
            step = SetupStep.ADD_CREDENTIAL,
            why = "这台主机还没有密码或私钥。",
        )

        lastError != null -> InstanceState.Unavailable(
            why = lastError,
            recovery = "检查网络与凭据后重新检测；如果提示 host key 变化，需要先确认是新机器。",
        )

        lastVerifiedAtMs == null -> InstanceState.Unavailable(
            why = "尚未验证连接。",
            recovery = "点「重新检测」做一次 TCP + 握手测试。",
        )

        else -> InstanceState.Ready
    }

    /**
     * App 宿主没有"一个状态"，只有一个能力集合：无障碍、存储、通知读取各自独立。
     * 一项都没有时才是不可用；部分缺失靠 subtitle 说清楚，不把整块判红。
     */
    fun appHost(capabilities: Set<Capability>): InstanceState =
        if (capabilities.isEmpty()) {
            InstanceState.Unavailable(
                why = "还没有任何设备能力可用。",
                recovery = "在设置 → 权限里授予存储；需要界面自动化再开无障碍服务。",
            )
        } else {
            InstanceState.Ready
        }

    fun appHostCapabilities(
        accessibilityEnabled: Boolean,
        allFilesAccess: Boolean,
    ): Set<Capability> = buildSet {
        if (accessibilityEnabled) add(Capability.DEVICE_UI)
        if (allFilesAccess) {
            add(Capability.FILE_READ)
            add(Capability.FILE_WRITE)
        }
    }

    /** App 宿主的一行能力摘要，例如「无障碍已开 · 存储可读写」。 */
    fun appHostSubtitle(capabilities: Set<Capability>): String {
        val parts = buildList {
            add(if (Capability.DEVICE_UI in capabilities) "无障碍已开" else "无障碍未开")
            add(if (Capability.FILE_WRITE in capabilities) "存储可读写" else "存储未授权")
        }
        return parts.joinToString(" · ")
    }
}
