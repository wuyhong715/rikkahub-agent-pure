package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.shizuku.PrivilegeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RootExecRules] — the gate and the refusal envelope `root_exec` hands the model.
 *
 * The important property is the negative one: **only** a broker that reports uid 0 may run. A
 * shell-UID broker and no-broker-at-all must both be refused, and the refusal has to say which one
 * it was *and* how to fix it, because the model reads nothing else.
 */
class RootExecRulesTest {

    @Test
    fun `only a root broker may run`() {
        assertTrue(RootExecRules.isAvailable(PrivilegeLevel.ROOT))
        assertFalse(RootExecRules.isAvailable(PrivilegeLevel.SHELL))
        assertFalse(RootExecRules.isAvailable(PrivilegeLevel.UNKNOWN))
    }

    @Test
    fun `privilege names match the wire vocabulary`() {
        assertEquals("root", RootExecRules.privilegeName(PrivilegeLevel.ROOT))
        assertEquals("shell", RootExecRules.privilegeName(PrivilegeLevel.SHELL))
        assertEquals("unknown", RootExecRules.privilegeName(PrivilegeLevel.UNKNOWN))
    }

    @Test
    fun `every level produces a complete envelope`() {
        PrivilegeLevel.values().forEach { level ->
            val envelope = RootExecRules.unavailableEnvelope(level)
            assertEquals(RootExecRules.ERROR_NOT_AVAILABLE, envelope["error"]!!.jsonPrimitive.content)
            assertEquals(
                RootExecRules.privilegeName(level),
                envelope["privilege"]!!.jsonPrimitive.content,
            )
            assertTrue(envelope["recovery"]!!.jsonPrimitive.content.isNotBlank())
        }
    }

    @Test
    fun `a shell broker refusal names the actual privilege and the way out`() {
        val envelope = RootExecRules.unavailableEnvelope(PrivilegeLevel.SHELL)
        val recovery = envelope["recovery"]!!.jsonPrimitive.content
        assertTrue("must name the UID it actually has", recovery.contains("shell UID"))
        assertTrue("must offer a way to get root", recovery.contains("Start Shizuku with root"))
        // ...and the cheaper alternative, so a command that never needed root is not blocked.
        assertTrue(recovery.contains("shizuku_exec"))
    }

    @Test
    fun `a missing broker refusal points at installing and starting Shizuku`() {
        val envelope = RootExecRules.unavailableEnvelope(PrivilegeLevel.UNKNOWN)
        val recovery = envelope["recovery"]!!.jsonPrimitive.content
        assertTrue(recovery.contains("Shizuku"))
        assertTrue(recovery.contains("Sui"))
    }
}
