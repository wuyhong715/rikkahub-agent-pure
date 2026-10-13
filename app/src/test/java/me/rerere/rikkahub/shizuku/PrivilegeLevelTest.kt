package me.rerere.rikkahub.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PrivilegeLevel.fromUid] — the mapping the Settings -> Shizuku privilege row
 * (and, later, the root tool's gate) is built on.
 *
 * The point of these tests is the *negative* case: nothing except a literal UID 0 may ever be
 * reported as root. A missing binder, a sentinel value, or an app UID must all degrade to
 * [PrivilegeLevel.UNKNOWN].
 */
class PrivilegeLevelTest {

    @Test
    fun `uid 0 is root`() {
        assertEquals(PrivilegeLevel.ROOT, PrivilegeLevel.fromUid(0))
    }

    @Test
    fun `uid 2000 is shell`() {
        assertEquals(PrivilegeLevel.SHELL, PrivilegeLevel.fromUid(2000))
    }

    @Test
    fun `a missing uid is unknown, never root`() {
        assertEquals(PrivilegeLevel.UNKNOWN, PrivilegeLevel.fromUid(null))
    }

    @Test
    fun `unexpected uids are unknown`() {
        // -1 if a future SDK returns a sentinel; an app UID if the plumbing ever crossed wires.
        assertEquals(PrivilegeLevel.UNKNOWN, PrivilegeLevel.fromUid(-1))
        assertEquals(PrivilegeLevel.UNKNOWN, PrivilegeLevel.fromUid(1000))
        assertEquals(PrivilegeLevel.UNKNOWN, PrivilegeLevel.fromUid(10_000))
    }

    @Test
    fun `the constants name the Android uids they claim to`() {
        assertEquals(0, PrivilegeLevel.ROOT_UID)
        assertEquals(2000, PrivilegeLevel.SHELL_UID)
    }
}
