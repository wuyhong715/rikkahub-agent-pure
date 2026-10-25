package me.rerere.rikkahub.data.runtime

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration

/**
 * Termux：长驻 shell、tmux 会话、apt、以及 transcribe 这类"必须借用 Termux 工具链"的能力。
 *
 * 一个必须小心的地方：`RUN_COMMAND` 在 Termux 处于后台时会**超时**而不是报错，表现为
 * `AllowExternalAppsMissing`。这不是故障，而是"需要一次前台交互"，所以映射成
 * [SetupStep.ENABLE_FEATURE] 的可行动作，而不是红色错误。
 */
class TermuxInstanceAdapter(
    private val context: Context,
) : InstanceAdapter {

    override val kind: InstanceKind = InstanceKind.TERMUX

    private val version = MutableStateFlow(0L)

    /** 上次烟测的结论；进程级就够，持久化由 TermuxPreferences 的 verifiedAt 负责。 */
    @Volatile
    private var lastProbe: TermuxProbeResult? = null

    @Volatile
    private var lastProbeAtMs: Long? = null

    override fun observe(): Flow<List<RuntimeInstance>> = version.map { build(lastProbeAtMs) }

    override suspend fun probe(id: InstanceId?): List<RuntimeInstance> {
        val result = mapState(TermuxIntegration.state(context))
            .takeIf { it != TermuxProbeResult.OK }
            ?: mapVerify(TermuxIntegration.verify(context))

        lastProbe = result
        lastProbeAtMs = System.currentTimeMillis()
        if (result == TermuxProbeResult.OK) {
            runCatching { TermuxIntegration.markVerifiedOk() }
        }
        version.update { it + 1 }
        return build(lastProbeAtMs)
    }

    override fun backedToolNames(): Set<String> = setOf(
        "termux_run_command",
        "termux_session_start",
        "termux_session_send",
        "termux_session_read",
        "termux_session_kill",
        "termux_session_list",
        "transcribe_audio_file",
        "whisper_status",
    )

    override fun actions(id: InstanceId): List<InstanceAction> {
        val state = build(lastProbeAtMs).first().state
        val step = (state as? InstanceState.NeedsSetup)?.step
        return when {
            step == SetupStep.INSTALL_APP -> listOf(
                InstanceAction(ACTION_OPEN_TERMUX, "安装 / 打开 Termux", SetupStep.INSTALL_APP),
            )

            step == SetupStep.GRANT_PERMISSION -> listOf(
                InstanceAction(ACTION_OPEN_TERMUX, "打开 Termux 授权", SetupStep.GRANT_PERMISSION),
            )

            step == SetupStep.ENABLE_FEATURE -> listOf(
                InstanceAction(ACTION_OPEN_TERMUX, "把 Termux 切到前台", SetupStep.ENABLE_FEATURE),
            )

            else -> listOf(
                InstanceAction(ACTION_OPEN_TERMUX, "打开 Termux", SetupStep.START_SERVICE),
            )
        }
    }

    override suspend fun invoke(id: InstanceId, actionId: String): ActionResult = when (actionId) {
        ACTION_OPEN_TERMUX -> runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
                ?: return ActionResult.Failed("没有安装 Termux")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult.Launched("已切到 Termux；回来后再点「重新检测」")
        }.getOrElse { ActionResult.Failed(it.message ?: "无法打开 Termux") }

        else -> ActionResult.Unsupported
    }

    override fun invalidate() {
        version.update { it + 1 }
    }

    private fun build(lastProbeAt: Long?): List<RuntimeInstance> {
        val stateName = runCatching { TermuxIntegration.state(context) }
            .getOrDefault(TermuxIntegration.State.NOT_INSTALLED)
        val effective = lastProbe ?: mapState(stateName).takeIf { it != TermuxProbeResult.OK }
        val state = InstanceStateMappers.termux(effective)
        val capabilities = if (state is InstanceState.Ready) {
            setOf(Capability.SHELL_EXEC, Capability.INTERACTIVE_SESSION)
        } else {
            emptySet()
        }
        val subtitle = when {
            state is InstanceState.Ready -> "已安装 · RUN_COMMAND 可用"
            stateName == TermuxIntegration.State.NOT_INSTALLED -> "未安装"
            else -> "未就绪"
        }
        return listOf(
            RuntimeInstance(
                id = InstanceId.local(InstanceKind.TERMUX),
                displayName = "Termux",
                subtitle = subtitle,
                state = state,
                capabilities = capabilities,
                lastProbeAt = lastProbeAt,
            ),
        )
    }

    private fun mapState(state: TermuxIntegration.State): TermuxProbeResult = when (state) {
        TermuxIntegration.State.NOT_INSTALLED -> TermuxProbeResult.NOT_INSTALLED
        TermuxIntegration.State.NO_PERMISSION -> TermuxProbeResult.NO_PERMISSION
        // 装了、权限也有：只能说明"渠道通了"，能不能真的跑要看烟测，交给 probe 的 verify。
        TermuxIntegration.State.READY -> TermuxProbeResult.OK
    }

    private fun mapVerify(result: TermuxIntegration.VerifyResult): TermuxProbeResult = when (result) {
        TermuxIntegration.VerifyResult.NotInstalled -> TermuxProbeResult.NOT_INSTALLED
        TermuxIntegration.VerifyResult.NoPermission -> TermuxProbeResult.NO_PERMISSION
        TermuxIntegration.VerifyResult.AllowExternalAppsMissing -> TermuxProbeResult.ALLOW_EXTERNAL_APPS_MISSING
        TermuxIntegration.VerifyResult.Ok -> TermuxProbeResult.OK
        is TermuxIntegration.VerifyResult.UnexpectedOutput -> TermuxProbeResult.FAILED
        is TermuxIntegration.VerifyResult.OtherError -> TermuxProbeResult.FAILED
    }

    private companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val ACTION_OPEN_TERMUX = "open_termux"
    }
}
