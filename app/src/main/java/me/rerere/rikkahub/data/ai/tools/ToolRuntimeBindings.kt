package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.runtime.Capability
import me.rerere.rikkahub.data.runtime.InstanceKind

/**
 * 工具组 → 它依赖的运行实例能力。
 *
 * 只记**结构性依赖**：这个组的工具离开那个后端就毫无意义。所以表很小、故意保守——
 * 一张会长大的表比没有表更糟，因为滤错的代价（能力从目录里消失）比多给几个工具大得多。
 *
 * 不在这里登记的东西：
 * - 只吃普通运行时权限的工具（相机、定位、短信……）：权限是系统对话框的事，调用失败会给出
 *   可读错误，比"悄悄看不见"好。
 * - 管理类工具（`save_ssh_host`、`list_ssh_hosts`）：它们的作用恰恰是在后端还不存在时创建它。
 */
data class InstanceBinding(
    val kind: InstanceKind,
    val capability: Capability,
)

object ToolRuntimeBindings {

    val byOption: Map<LocalToolOption, InstanceBinding> = mapOf(
        // shizuku_exec / root_exec —— 没有 binder 时两个都跑不了。
        LocalToolOption.Shizuku to InstanceBinding(InstanceKind.SHIZUKU, Capability.SHELL_EXEC),
        // termux_run_command / session_* / transcribe_audio_file —— 全部要 RUN_COMMAND。
        LocalToolOption.Termux to InstanceBinding(InstanceKind.TERMUX, Capability.SHELL_EXEC),
        // ssh_exec / upload / download —— 至少一台可用主机才有意义。
        LocalToolOption.Ssh to InstanceBinding(InstanceKind.SSH, Capability.SHELL_EXEC),
        // 截图 / 点击 / 手势 —— 无障碍服务是硬前提。
        LocalToolOption.ScreenAutomation to InstanceBinding(InstanceKind.APP_HOST, Capability.DEVICE_UI),
    )

    fun bindingFor(option: LocalToolOption): InstanceBinding? = byOption[option]
}
