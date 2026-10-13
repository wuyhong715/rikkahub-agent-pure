package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The static knowledge behind the blacklist-era approval decision: the non-waivable hard floor,
 * the opaque `mcp__*` tools that ride on it, and the curated "recommended blacklist" set the
 * Settings screen offers.
 */
class ToolApprovalDefaultsTest {

    @Test
    fun `privilege-escalation tools are on the hard floor`() {
        assertTrue(ToolApprovalDefaults.isHardFloor("eval_javascript"))
        assertTrue(ToolApprovalDefaults.isHardFloor("keystore_decrypt"))
        assertTrue(ToolApprovalDefaults.isHardFloor("grant_directory_access"))
        assertTrue(ToolApprovalDefaults.isHardFloor("mcp_add"))
        assertTrue(ToolApprovalDefaults.isHardFloor("nfc_write_tag"))
    }

    @Test
    fun `opaque mcp relayed tools are on the hard floor`() {
        assertTrue(ToolApprovalDefaults.isHardFloor("mcp__server__do_something"))
        assertTrue(ToolApprovalDefaults.isHardFloor("mcp__"))
    }

    @Test
    fun `ordinary tools are not on the floor`() {
        // launch_activity (issue #42) is a normal side-effecting tool now: it asks only when
        // the user blacklists it, because the built-in "always ask" set is gone.
        assertFalse(ToolApprovalDefaults.isHardFloor("launch_activity"))
        assertFalse(ToolApprovalDefaults.isHardFloor("read_file"))
        assertFalse(ToolApprovalDefaults.isHardFloor(""))
    }

    @Test
    fun `the recommended set still lists the classic side-effecting tools`() {
        assertTrue("launch_activity" in ToolApprovalDefaults.DANGEROUS_TOOLS)
        assertTrue("termux_run_command" in ToolApprovalDefaults.DANGEROUS_TOOLS)
        assertTrue("write_text_file" in ToolApprovalDefaults.DANGEROUS_TOOLS)
    }

    // ------------------------------------------------------- blacklisted-by-default

    @Test
    fun `the root tool is blacklisted and pinned by default`() {
        assertTrue(ToolApprovalDefaults.ROOT_EXEC_TOOL_NAME in ToolApprovalDefaults.DEFAULT_BLACKLISTED)
        assertTrue(ToolApprovalDefaults.ROOT_EXEC_TOOL_NAME in ToolApprovalDefaults.PINNED_TOOLS)
        assertTrue(ToolApprovalDefaults.ROOT_EXEC_TOOL_NAME in ToolApprovalDefaults.DANGEROUS_TOOLS)
    }

    @Test
    fun `a default is blacklisted, not on the floor, so the user can take it off`() {
        // The whole point of shipping root_exec blacklisted (rather than flooring it) is that the
        // user *can* remove it, through the risk confirmation. If it ever moves onto the floor that
        // becomes impossible and the Settings switch silently stops meaning anything.
        ToolApprovalDefaults.DEFAULT_BLACKLISTED.forEach { name ->
            assertFalse("$name must stay removable", ToolApprovalDefaults.isHardFloor(name))
        }
    }

    @Test
    fun `every pinned tool is blacklisted by default`() {
        ToolApprovalDefaults.PINNED_TOOLS.forEach { name ->
            assertTrue(
                "$name is pinned in the UI but not blacklisted by default",
                name in ToolApprovalDefaults.DEFAULT_BLACKLISTED,
            )
        }
    }

    @Test
    fun `the defaults are unioned in until the first edit`() {
        val unseeded = ToolApprovalDefaults.effectiveBlacklist(emptySet(), seeded = false)
        assertTrue(ToolApprovalDefaults.ROOT_EXEC_TOOL_NAME in unseeded)
        assertTrue("read_file" in ToolApprovalDefaults.effectiveBlacklist(setOf("read_file"), seeded = false))
    }

    @Test
    fun `once seeded the stored set is the whole truth, so a removal sticks`() {
        // This is what makes "take it off the blacklist" possible at all: after the first edit the
        // defaults are not re-added on every read.
        assertEquals(emptySet<String>(), ToolApprovalDefaults.effectiveBlacklist(emptySet(), seeded = true))
        assertEquals(
            setOf("read_file"),
            ToolApprovalDefaults.effectiveBlacklist(setOf("read_file"), seeded = true),
        )
    }
}
