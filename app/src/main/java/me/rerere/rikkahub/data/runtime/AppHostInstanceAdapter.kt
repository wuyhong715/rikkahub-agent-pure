package me.rerere.rikkahub.data.runtime

import android.content.Context
import android.content.Intent
import android.os.Process
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper

/**
 * App 宿主：这个应用自己的 uid 在设备上能做什么。
 *
 * 它此前**不是一个对象**——没有状态、没有入口、无法被体检，只能靠"gated 组 + 医生页手写映射"
 * 间接表达。这里给它一个身份，但刻意不把它压成一个布尔：无障碍、存储、通知读取是互相独立的
 * 授权，任何一项缺失都不该让整块判红。
 */
class AppHostInstanceAdapter(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : InstanceAdapter {

    override val kind: InstanceKind = InstanceKind.APP_HOST

    private val version = MutableStateFlow(0L)

    override fun observe(): Flow<List<RuntimeInstance>> = version.map { build(lastProbeAt = null) }

    override suspend fun probe(id: InstanceId?): List<RuntimeInstance> = build(lastProbeAt = clock())

    /**
     * App 宿主没有 shell，它的工具面是"设备能力"族。这里只列最常用的入口，
     * 不试图穷举 140 个工具——详情页要回答"这个实例能干什么"，不是再复制一份目录。
     */
    override fun backedToolNames(): Set<String> = setOf(
        "read_window_tree",
        "take_screenshot",
        "tap",
        "swipe",
        "read_file",
        "list_files",
        "write_text_file",
    )

    override fun actions(id: InstanceId): List<InstanceAction> =
        listOf(
            InstanceAction(
                id = ACTION_OPEN_PERMISSIONS,
                label = "打开权限设置",
                step = SetupStep.GRANT_PERMISSION,
            ),
            InstanceAction(
                id = ACTION_OPEN_ACCESSIBILITY,
                label = "打开无障碍设置",
                step = SetupStep.ENABLE_FEATURE,
            ),
        )

    override suspend fun invoke(id: InstanceId, actionId: String): ActionResult = when (actionId) {
        ACTION_OPEN_PERMISSIONS ->
            launch(PermissionHelper.allFilesAccessIntent(context), "已打开「所有文件访问权限」")

        ACTION_OPEN_ACCESSIBILITY ->
            launch(PermissionHelper.accessibilitySettingsIntent(), "已打开无障碍设置")

        else -> ActionResult.Unsupported
    }

    override fun invalidate() {
        version.update { it + 1 }
    }

    private fun build(lastProbeAt: Long?): List<RuntimeInstance> {
        val capabilities = InstanceStateMappers.appHostCapabilities(
            accessibilityEnabled = PermissionHelper.hasAccessibilityService(context),
            allFilesAccess = PermissionHelper.hasAllFilesAccess(context),
        )
        return listOf(
            RuntimeInstance(
                id = InstanceId.local(InstanceKind.APP_HOST),
                displayName = "App 宿主",
                subtitle = "${InstanceStateMappers.appHostSubtitle(capabilities)} · uid ${Process.myUid()}",
                state = InstanceStateMappers.appHost(capabilities),
                capabilities = capabilities,
                lastProbeAt = lastProbeAt,
            ),
        )
    }

    private fun launch(intent: Intent, message: String): ActionResult = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ActionResult.Launched(message)
    }.getOrElse { ActionResult.Failed(it.message ?: "无法打开系统界面") }

    companion object {
        const val ACTION_OPEN_PERMISSIONS = "open_permissions"
        const val ACTION_OPEN_ACCESSIBILITY = "open_accessibility"
    }
}
