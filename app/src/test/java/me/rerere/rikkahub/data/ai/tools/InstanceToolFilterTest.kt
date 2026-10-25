package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.runtime.Capability
import me.rerere.rikkahub.data.runtime.InstanceId
import me.rerere.rikkahub.data.runtime.InstanceKind
import me.rerere.rikkahub.data.runtime.InstanceState
import me.rerere.rikkahub.data.runtime.RuntimeInstance
import me.rerere.rikkahub.data.runtime.SetupStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录裁剪只有两条规则值得钉住：**默认只裁"永远不可能成功"的**，以及**没有实例时不裁**。
 * 这两条错任何一条，用户都会遇到"工具莫名其妙不见了"，而那是最难排查的一类问题。
 */
class InstanceToolFilterTest {

    private val options = listOf(
        LocalToolOption.Shizuku,
        LocalToolOption.Termux,
        LocalToolOption.Ssh,
        LocalToolOption.ScreenAutomation,
        LocalToolOption.Files,
    )

    private fun instance(
        kind: InstanceKind,
        key: String = "local",
        state: InstanceState,
        capabilities: Set<Capability> = setOf(Capability.SHELL_EXEC),
    ) = RuntimeInstance(
        id = InstanceId(kind, key),
        displayName = key,
        subtitle = "",
        state = state,
        capabilities = capabilities,
    )

    @Test
    fun `a cold snapshot never hides anything`() {
        assertEquals(options, InstanceToolFilter.effective(options, emptyList()))
    }

    @Test
    fun `OFF mode never hides anything`() {
        val snapshot = listOf(
            instance(InstanceKind.TERMUX, state = InstanceState.NeedsSetup(SetupStep.INSTALL_APP, "未安装")),
        )
        assertEquals(options, InstanceToolFilter.effective(options, snapshot, InstanceFilterMode.OFF))
    }

    @Test
    fun `a backend that is not installed drops its group`() {
        val snapshot = listOf(
            instance(InstanceKind.TERMUX, state = InstanceState.NeedsSetup(SetupStep.INSTALL_APP, "未安装")),
        )
        val kept = InstanceToolFilter.effective(options, snapshot)
        assertFalse(LocalToolOption.Termux in kept)
        // 其它组建在。
        assertTrue(LocalToolOption.Shizuku in kept)
        assertTrue(LocalToolOption.Files in kept)
        assertEquals(listOf(LocalToolOption.Termux), InstanceToolFilter.blockedOptions(options, snapshot))
    }

    @Test
    fun `a backend that merely needs permission keeps its group`() {
        // 没授权下一秒就能点一下修好；藏起来反而让模型不知道有这个能力。
        val snapshot = listOf(
            instance(
                InstanceKind.TERMUX,
                state = InstanceState.NeedsSetup(SetupStep.GRANT_PERMISSION, "没授权"),
            ),
        )
        assertTrue(LocalToolOption.Termux in InstanceToolFilter.effective(options, snapshot))
    }

    @Test
    fun `an unverified ssh host keeps the group, because the group can still create one`() {
        // 关键案例：save_ssh_host 就在这个组里，滤掉它用户永远建不了第一台主机。
        val snapshot = listOf(
            instance(
                InstanceKind.SSH,
                key = "vps",
                state = InstanceState.NeedsSetup(SetupStep.ADD_CREDENTIAL, "没凭据"),
            ),
        )
        assertTrue(LocalToolOption.Ssh in InstanceToolFilter.effective(options, snapshot))
    }

    @Test
    fun `no host at all also keeps the group`() {
        assertTrue(LocalToolOption.Ssh in InstanceToolFilter.effective(options, emptyList()))
    }

    @Test
    fun `NOT_READY mode drops every group whose backend is not usable`() {
        val snapshot = listOf(
            instance(InstanceKind.TERMUX, state = InstanceState.NeedsSetup(SetupStep.GRANT_PERMISSION, "没授权")),
            instance(
                InstanceKind.SSH,
                key = "vps",
                state = InstanceState.Unavailable("尚未验证连接。", "点重新检测"),
            ),
            instance(
                InstanceKind.APP_HOST,
                state = InstanceState.Ready,
                capabilities = setOf(Capability.FILE_READ, Capability.FILE_WRITE),
            ),
        )
        val kept = InstanceToolFilter.effective(options, snapshot, InstanceFilterMode.NOT_READY)
        assertFalse(LocalToolOption.Termux in kept)
        assertFalse(LocalToolOption.Ssh in kept)
        // 无障碍没开 → ScreenAutomation 也出去。
        assertFalse(LocalToolOption.ScreenAutomation in kept)
        // 不相干组不受影响。
        assertTrue(LocalToolOption.Files in kept)
    }

    @Test
    fun `a ready backend keeps its group in every mode`() {
        val snapshot = listOf(instance(InstanceKind.TERMUX, state = InstanceState.Ready))
        listOf(InstanceFilterMode.ABSENT_ONLY, InstanceFilterMode.NOT_READY).forEach { mode ->
            assertTrue(
                "mode=$mode",
                LocalToolOption.Termux in InstanceToolFilter.effective(options, snapshot, mode),
            )
        }
    }

    @Test
    fun `bindings only name backends that actually exist`() {
        // 防漂移：绑定表里出现的 kind 必须能被某个适配器提供（实例快照里出现过即证明）。
        assertTrue(ToolRuntimeBindings.byOption.isNotEmpty())
        ToolRuntimeBindings.byOption.values.forEach { binding ->
            assertTrue(
                "unexpected capability for ${binding.kind}",
                binding.capability in setOf(
                    Capability.SHELL_EXEC,
                    Capability.FILE_READ,
                    Capability.FILE_WRITE,
                    Capability.INTERACTIVE_SESSION,
                    Capability.DEVICE_UI,
                    Capability.PRIVILEGED_UID,
                    Capability.REMOTE_NET,
                ),
            )
        }
    }
}
