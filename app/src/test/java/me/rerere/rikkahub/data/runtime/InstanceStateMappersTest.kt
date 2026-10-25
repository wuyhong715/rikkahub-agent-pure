package me.rerere.rikkahub.data.runtime

import me.rerere.rikkahub.shizuku.ShizukuStatus
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态映射是全抽象里唯一"每个后端各写一句"的地方，因此也是最容易悄悄说谎的地方：
 * 一个映射写错，设置页就会对一个根本用不了的后端显示绿色。这里逐条钉住。
 */
class InstanceStateMappersTest {

    @Test
    fun `shizuku maps its four states onto the shared vocabulary`() {
        assertEquals(
            InstanceState.NeedsSetup(SetupStep.INSTALL_APP, "没有检测到 Shizuku（或 Sui）：当前没有任何提权通道。"),
            InstanceStateMappers.shizuku(ShizukuStatus.NOT_INSTALLED),
        )
        assertTrue(InstanceStateMappers.shizuku(ShizukuStatus.NOT_RUNNING) is InstanceState.NeedsSetup)
        assertTrue(InstanceStateMappers.shizuku(ShizukuStatus.PERMISSION_DENIED) is InstanceState.NeedsSetup)
        assertEquals(InstanceState.Ready, InstanceStateMappers.shizuku(ShizukuStatus.READY))
    }

    @Test
    fun `root is a second dimension of the shizuku instance, not a separate state`() {
        // binder 活着只证明 uid 2000；broker 是 root 才多出 PRIVILEGED_UID。
        assertEquals(
            setOf(Capability.SHELL_EXEC),
            InstanceStateMappers.shizukuCapabilities(brokerUid = 2000),
        )
        assertEquals(
            setOf(Capability.SHELL_EXEC, Capability.PRIVILEGED_UID),
            InstanceStateMappers.shizukuCapabilities(brokerUid = 0),
        )
        // 未知 uid 不能当成 root。
        assertEquals(
            setOf(Capability.SHELL_EXEC),
            InstanceStateMappers.shizukuCapabilities(brokerUid = null),
        )
    }

    @Test
    fun `workspace installing is busy, broken is unavailable with a recovery hint`() {
        assertTrue(InstanceStateMappers.workspace(WorkspaceShellStatus.INSTALLING) is InstanceState.Busy)

        val broken = InstanceStateMappers.workspace(WorkspaceShellStatus.BROKEN)
        assertTrue(broken is InstanceState.Unavailable)
        assertFalse((broken as InstanceState.Unavailable).recovery.isNullOrBlank())

        val disabled = InstanceStateMappers.workspace(WorkspaceShellStatus.DISABLED)
        assertEquals(SetupStep.INSTALL_ROOTFS, (disabled as InstanceState.NeedsSetup).step)
    }

    @Test
    fun `an unknown stored workspace status is unavailable, never ready`() {
        val state = InstanceStateMappers.workspace("SOMETHING_FROM_A_NEWER_BUILD")
        assertTrue(state is InstanceState.Unavailable)
    }

    @Test
    fun `termux background refusal is actionable setup, not a hard failure`() {
        // RUN_COMMAND 在 Termux 后台时是超时，表现为 allow-external-apps 缺失。
        // 判成故障会让设置页永远红着，而用户其实只需要把 Termux 切到前台。
        val state = InstanceStateMappers.termux(TermuxProbeResult.ALLOW_EXTERNAL_APPS_MISSING)
        assertEquals(SetupStep.ENABLE_FEATURE, (state as InstanceState.NeedsSetup).step)

        assertEquals(InstanceState.Ready, InstanceStateMappers.termux(TermuxProbeResult.OK))
        assertTrue(InstanceStateMappers.termux(null) is InstanceState.Unavailable)
    }

    @Test
    fun `ssh separates three different situations`() {
        val noCredential = InstanceStateMappers.ssh(
            hasCredential = false,
            lastVerifiedAtMs = null,
            lastError = null,
        )
        assertEquals(SetupStep.ADD_CREDENTIAL, (noCredential as InstanceState.NeedsSetup).step)

        val neverTested = InstanceStateMappers.ssh(
            hasCredential = true,
            lastVerifiedAtMs = null,
            lastError = null,
        )
        assertTrue(neverTested is InstanceState.Unavailable)
        assertTrue((neverTested as InstanceState.Unavailable).why.contains("尚未验证"))

        val failed = InstanceStateMappers.ssh(
            hasCredential = true,
            lastVerifiedAtMs = null,
            lastError = "认证失败",
        )
        assertTrue((failed as InstanceState.Unavailable).why.contains("认证失败"))

        assertEquals(
            InstanceState.Ready,
            InstanceStateMappers.ssh(hasCredential = true, lastVerifiedAtMs = 1_000L, lastError = null),
        )
    }

    @Test
    fun `app host is a capability vector, and only fully empty is unavailable`() {
        val none = InstanceStateMappers.appHost(emptySet())
        assertTrue(none is InstanceState.Unavailable)

        val partial = InstanceStateMappers.appHostCapabilities(
            accessibilityEnabled = true,
            allFilesAccess = false,
        )
        assertEquals(setOf(Capability.DEVICE_UI), partial)
        assertEquals(InstanceState.Ready, InstanceStateMappers.appHost(partial))
        assertEquals("无障碍已开 · 存储未授权", InstanceStateMappers.appHostSubtitle(partial))
    }

    @Test
    fun `supports requires both the capability and a ready state`() {
        val instance = RuntimeInstance(
            id = InstanceId.local(InstanceKind.TERMUX),
            displayName = "Termux",
            subtitle = "",
            state = InstanceState.NeedsSetup(SetupStep.INSTALL_APP, "未安装"),
            capabilities = setOf(Capability.SHELL_EXEC),
        )
        assertFalse(instance.supports(Capability.SHELL_EXEC))

        val ready = instance.copy(state = InstanceState.Ready)
        assertTrue(ready.supports(Capability.SHELL_EXEC))
        assertFalse(ready.supports(Capability.PRIVILEGED_UID))
    }
}
