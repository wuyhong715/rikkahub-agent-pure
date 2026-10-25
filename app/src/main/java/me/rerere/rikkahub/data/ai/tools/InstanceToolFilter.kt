package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.runtime.InstanceState
import me.rerere.rikkahub.data.runtime.RuntimeInstance
import me.rerere.rikkahub.data.runtime.SetupStep

/** 目录按实例状态的裁剪力度。 */
enum class InstanceFilterMode {
    /** 不裁。出问题时的一键回退。 */
    OFF,

    /**
     * 只裁"**确定永远不可能成功**"的组：后端应用根本没装（`NeedsSetup(INSTALL_APP)`）。
     *
     * 这是默认值，理由见 [InstanceToolFilter] 的说明。待授权、待装 rootfs、待验证都不裁。
     */
    ABSENT_ONLY,

    /**
     * 连"暂时不行"也裁掉（未授权、未装 rootfs、SSH 没验证）。
     *
     * 实验性：目录会随状态抖动，模型上一轮见过的工具这一轮可能消失。不建议默认开。
     */
    NOT_READY,
}

/**
 * 按运行实例状态裁剪工具目录。
 *
 * ## 为什么默认只裁"确定不可能"
 *
 * `LocalToolGates` 已经论证过：目录喂给 embedding 检索，**大小不是关键、排名才是**，所以
 * "把暂时不能用的组藏起来"没有收益。真正有害的只有一种情况：工具永远不可能成功却还在目录里，
 * 模型反复挑中它、反复失败。那种情况就是后端应用没装。
 *
 * "没授权 / 没装 rootfs / 没验证"不属于这一类——它们下一秒就可能被用户点一下修好，
 * 而且调用失败会返回带 `recovery` 的统一包络（P2），比"看不见这个能力"信息量更大。
 *
 * ## 没有实例时不裁
 *
 * SSH 一个主机都没有时不裁 `Ssh` 组：那个组里还有 `save_ssh_host` / `list_ssh_hosts`，
 * 正是用来创建第一台主机的。滤掉它会让"添加主机"这件事永远做不成。
 *
 * 纯 Kotlin，可在裸 JVM 上测。
 */
object InstanceToolFilter {

    val DEFAULT_MODE: InstanceFilterMode = InstanceFilterMode.ABSENT_ONLY

    fun effective(
        options: List<LocalToolOption>,
        instances: List<RuntimeInstance>,
        mode: InstanceFilterMode = DEFAULT_MODE,
    ): List<LocalToolOption> {
        if (mode == InstanceFilterMode.OFF || instances.isEmpty()) return options
        val blocked = blockedOptions(options, instances, mode).toSet()
        return options.filterNot { it in blocked }
    }

    /** 被裁掉的组。独立出来是为了能在设置页/日志里说清"为什么少了几个工具"。 */
    fun blockedOptions(
        options: List<LocalToolOption>,
        instances: List<RuntimeInstance>,
        mode: InstanceFilterMode = DEFAULT_MODE,
    ): List<LocalToolOption> {
        if (mode == InstanceFilterMode.OFF || instances.isEmpty()) return emptyList()
        return options.filter { isBlocked(it, instances, mode) }
    }

    private fun isBlocked(
        option: LocalToolOption,
        instances: List<RuntimeInstance>,
        mode: InstanceFilterMode,
    ): Boolean {
        val binding = ToolRuntimeBindings.bindingFor(option) ?: return false
        val candidates = instances.filter { it.id.kind == binding.kind }
        // 该后端一个实例都没有：组里的管理类工具仍然有用（见类文档）。
        if (candidates.isEmpty()) return false

        return when (mode) {
            InstanceFilterMode.OFF -> false

            InstanceFilterMode.ABSENT_ONLY -> candidates.all { instance ->
                val state = instance.state
                state is InstanceState.NeedsSetup && state.step == SetupStep.INSTALL_APP
            }

            InstanceFilterMode.NOT_READY -> candidates.none { it.supports(binding.capability) }
        }
    }
}
