package me.rerere.rikkahub.data.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一包络的契约测试。
 *
 * 三条硬要求，任何一条破了模型就会重新退化成"每个后端一套说法"：
 * - 失败一定有 `recovery`（可执行的下一步），否则模型只能猜；
 * - 失败一定带 `instance`，否则用户看到"连不上"却不知道连的是哪台；
 * - 既有键名一个都不能改，`decorate` 只追加、不覆盖。
 */
class InstanceEnvelopesTest {

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun `not-ready carries the instance, the state and a runnable recovery for every backend`() {
        val states = listOf(
            InstanceKind.APP_HOST to InstanceState.Unavailable("没有任何设备能力。", null),
            InstanceKind.SHIZUKU to InstanceState.NeedsSetup(SetupStep.GRANT_PERMISSION, "没有授权。"),
            InstanceKind.WORKSPACE to InstanceState.NeedsSetup(SetupStep.INSTALL_ROOTFS, "没装 rootfs。"),
            InstanceKind.SSH to InstanceState.NeedsSetup(SetupStep.ADD_CREDENTIAL, "没有凭据。"),
            InstanceKind.TERMUX to InstanceState.NeedsSetup(SetupStep.ENABLE_FEATURE, "没开 allow-external-apps。"),
        )

        states.forEach { (kind, state) ->
            val id = InstanceId(kind, "k-${kind.name}")
            val envelope = InstanceEnvelopes.notReady(id, state)

            assertEquals(InstanceEnvelopes.ERROR_NOT_READY, envelope.str("error"))
            assertEquals(id.serial, envelope.str("instance"))
            assertEquals(InstanceEnvelopes.stateName(state), envelope.str("state"))
            assertNotNull(envelope.str("why"))
            assertTrue("$kind 的 recovery 不能为空", !envelope.str("recovery").isNullOrBlank())
        }
    }

    @Test
    fun `needs-setup also names the concrete step so the model can say what to fix`() {
        val envelope = InstanceEnvelopes.notReady(
            InstanceId.local(InstanceKind.TERMUX),
            InstanceState.NeedsSetup(SetupStep.ENABLE_FEATURE, "没开 allow-external-apps。"),
        )
        assertEquals("enable_feature", envelope.str("step"))
        assertTrue(envelope.str("recovery")!!.contains("allow-external-apps"))
    }

    @Test
    fun `busy is reported as busy, not as a failure of the command`() {
        val envelope = InstanceEnvelopes.notReady(
            InstanceId(InstanceKind.WORKSPACE, "ws-1"),
            InstanceState.Busy("正在安装 Linux 环境"),
        )
        assertEquals("busy", envelope.str("state"))
        assertEquals("正在安装 Linux 环境", envelope.str("why"))
        assertNotNull(envelope.str("recovery"))
    }

    @Test
    fun `decorate keeps every existing key and only appends`() {
        val original = buildJsonObject {
            put("error", "auth_failed")
            put("host", "example.com")
            put("recovery", "检查凭据。")
        }

        val decorated = InstanceEnvelopes.decorate(
            original,
            InstanceId(InstanceKind.SSH, "vps"),
            InstanceState.Unavailable("认证失败", "检查凭据。"),
        )

        assertEquals("auth_failed", decorated.str("error"))
        assertEquals("example.com", decorated.str("host"))
        assertEquals("检查凭据。", decorated.str("recovery"))
        assertEquals("ssh:vps", decorated.str("instance"))
        assertEquals("unavailable", decorated.str("state"))
    }

    @Test
    fun `decorate never overwrites an instance that is already there`() {
        val original = buildJsonObject {
            put("error", "timeout")
            put("instance", "termux:local")
        }
        val decorated = InstanceEnvelopes.decorate(original, InstanceId.local(InstanceKind.SHIZUKU))
        assertEquals("termux:local", decorated.str("instance"))
    }

    @Test
    fun `decorate leaves successful payloads alone`() {
        val success = buildJsonObject {
            put("stdout", "ok")
            put("exit_code", 0)
        }
        assertEquals(success, InstanceEnvelopes.decorate(success, InstanceId.local(InstanceKind.SSH)))
        assertNull(InstanceEnvelopes.decorate(success, InstanceId.local(InstanceKind.SSH)).str("instance"))
    }

    @Test
    fun `decorate fills in a recovery when the backend forgot one`() {
        val bare = buildJsonObject { put("error", "connect_failed") }
        val decorated = InstanceEnvelopes.decorate(bare, InstanceId.local(InstanceKind.SSH))
        assertEquals(InstanceEnvelopes.GENERIC_RECOVERY, decorated.str("recovery"))
    }

    @Test
    fun `failure helper refuses to let extras shadow the reserved keys`() {
        val envelope = InstanceEnvelopes.failure(
            id = InstanceId(InstanceKind.WORKSPACE, "ws-1"),
            error = "workspace_shell_failed",
            recovery = "重装 rootfs。",
            extras = buildJsonObject {
                put("error", "shadowed")
                put("instance", "shadowed")
                put("stderr", "bash: not found")
            },
        )
        assertEquals("workspace_shell_failed", envelope.str("error"))
        assertEquals("workspace:ws-1", envelope.str("instance"))
        assertEquals("bash: not found", envelope.str("stderr"))
    }

    @Test
    fun `isFailure only reacts to a non-blank error`() {
        assertTrue(InstanceEnvelopes.isFailure(buildJsonObject { put("error", "timeout") }))
        assertFalse(InstanceEnvelopes.isFailure(buildJsonObject { put("error", " ") }))
        assertFalse(InstanceEnvelopes.isFailure(buildJsonObject { put("stdout", "ok") }))
    }
}
