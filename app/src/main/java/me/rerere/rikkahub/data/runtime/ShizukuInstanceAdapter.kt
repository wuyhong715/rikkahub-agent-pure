package me.rerere.rikkahub.data.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.shizuku.ShizukuManager
import me.rerere.rikkahub.shizuku.ShizukuStatus
import rikka.shizuku.Shizuku

/**
 * Shizuku（含 Sui）：adb shell 同级的提权通道，root 是它的第二维。
 *
 * `ShizukuStatus.READY` 只说明 binder 活着且授权给了本应用——broker 可能是 uid 2000 也可能是
 * uid 0，所以能力按 [ShizukuManager.uid] 算，`root_exec` 绑定的是 [Capability.PRIVILEGED_UID]。
 * 把这两件事压成一个状态，会在"Shizuku 显示就绪但 root_exec 一直失败"时无法解释。
 */
class ShizukuInstanceAdapter(
    private val context: Context,
) : InstanceAdapter {

    override val kind: InstanceKind = InstanceKind.SHIZUKU

    private val version = MutableStateFlow(0L)

    private val binderReceived = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() = invalidate()
    }

    private val binderDead = object : Shizuku.OnBinderDeadListener {
        override fun onBinderDead() = invalidate()
    }

    init {
        // 单例适配器活到进程结束，因此不注销：监听器的代价是一次静态注册。
        runCatching { ShizukuManager.addBinderReceivedListener(binderReceived) }
        runCatching { ShizukuManager.addBinderDeadListener(binderDead) }
    }

    override fun observe(): Flow<List<RuntimeInstance>> = version.map { build(lastProbeAt = null) }

    /**
     * Shizuku 的探测很便宜（一次 binder 调用），但没必要每次读都做——`observe()` 已经读了一遍。
     * 这里保留真实读一次，让「重新检测」有明确语义：状态是此刻问出来的。
     */
    override suspend fun probe(id: InstanceId?): List<RuntimeInstance> = build(lastProbeAt = System.currentTimeMillis())

    override fun backedToolNames(): Set<String> = setOf("shizuku_exec", "root_exec")

    override fun actions(id: InstanceId): List<InstanceAction> = when (val state = build(null).first().state) {
        is InstanceState.NeedsSetup -> when (state.step) {
            SetupStep.INSTALL_APP -> listOf(
                InstanceAction(ACTION_INSTALL, "下载 Shizuku", SetupStep.INSTALL_APP),
            )

            SetupStep.START_SERVICE -> listOf(
                InstanceAction(ACTION_OPEN_APP, "打开 Shizuku 应用", SetupStep.START_SERVICE),
            )

            SetupStep.GRANT_PERMISSION -> listOf(
                InstanceAction(ACTION_REQUEST_PERMISSION, "申请 Shizuku 授权", SetupStep.GRANT_PERMISSION),
            )

            else -> emptyList()
        }

        else -> emptyList()
    }

    override suspend fun invoke(id: InstanceId, actionId: String): ActionResult = when (actionId) {
        ACTION_INSTALL -> launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")),
            "已打开 Shizuku 下载页",
        )

        ACTION_OPEN_APP -> launch(
            context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")),
            "已打开 Shizuku",
        )

        ACTION_REQUEST_PERMISSION -> runCatching {
            ShizukuManager.requestPermission()
            ActionResult.Done
        }.getOrElse { ActionResult.Failed(it.message ?: "无法弹出授权对话框") }

        else -> ActionResult.Unsupported
    }

    override fun invalidate() {
        version.update { it + 1 }
    }

    private fun build(lastProbeAt: Long?): List<RuntimeInstance> {
        val status = runCatching { ShizukuManager.status(context) }.getOrDefault(ShizukuStatus.NOT_RUNNING)
        val uid = if (status == ShizukuStatus.READY) runCatching { ShizukuManager.uid() }.getOrNull() else null
        val capabilities =
            if (status == ShizukuStatus.READY) InstanceStateMappers.shizukuCapabilities(uid) else emptySet()

        val subtitle = when {
            status != ShizukuStatus.READY -> "不可用"
            uid == 0 -> "broker 以 root 运行（uid 0）"
            uid != null -> "uid $uid（adb shell 同级）"
            else -> "已授权"
        }

        return listOf(
            RuntimeInstance(
                id = InstanceId.local(InstanceKind.SHIZUKU),
                displayName = "Shizuku",
                subtitle = subtitle,
                state = InstanceStateMappers.shizuku(status),
                capabilities = capabilities,
                lastProbeAt = lastProbeAt,
            ),
        )
    }

    private fun launch(intent: Intent, message: String): ActionResult = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ActionResult.Launched(message)
    }.getOrElse { ActionResult.Failed(it.message ?: "无法打开目标界面") }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val ACTION_INSTALL = "install"
        const val ACTION_OPEN_APP = "open_app"
        const val ACTION_REQUEST_PERMISSION = "request_permission"
    }
}
