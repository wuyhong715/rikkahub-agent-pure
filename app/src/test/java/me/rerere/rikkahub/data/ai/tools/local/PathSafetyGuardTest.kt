package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import me.rerere.rikkahub.BuildConfig

class PathSafetyGuardTest {

    // ---- valid paths ----

    @Test fun `sdcard download path is allowed`() {
        assertNull(PathSafetyGuard.check("/sdcard/Download/song.mp3"))
    }

    @Test fun `storage emulated path is allowed`() {
        assertNull(PathSafetyGuard.check("/storage/emulated/0/Download"))
    }

    @Test fun `own app data path is allowed`() {
        assertNull(PathSafetyGuard.check("/data/data/${BuildConfig.APPLICATION_ID}/files/prefs.json"))
    }

    @Test fun `our own cache and datastore are blocked`() {
        // The file tools run without approval, and these two hold the plaintext provider keys
        // (datastore/) and decoded payloads (cache/). BrowserTools and JsSkillRunner already fence
        // them off from prompt injection; a direct read_file must not be the way around it.
        assertNotNull(PathSafetyGuard.check("/data/data/${BuildConfig.APPLICATION_ID}/cache/tmp.bin"))
        assertNotNull(
            PathSafetyGuard.check("/data/data/${BuildConfig.APPLICATION_ID}/files/datastore/settings.preferences_pb"),
        )
        assertNotNull(
            PathSafetyGuard.check("/data/data/${BuildConfig.APPLICATION_ID}/databases/rikkahub.db"),
        )
        // The workspace and the media folders keep working.
        assertNull(PathSafetyGuard.check("/data/data/${BuildConfig.APPLICATION_ID}/files/agent_workspace/notes.md"))
        assertNull(PathSafetyGuard.check("/data/data/${BuildConfig.APPLICATION_ID}/files/upload/x.png"))
    }

    @Test fun `external files dir is allowed`() {
        assertNull(PathSafetyGuard.check("/storage/emulated/0/Android/data/${BuildConfig.APPLICATION_ID}/files"))
    }

    // ---- system path blocks ----

    @Test fun `system root is blocked`() {
        val v = PathSafetyGuard.check("/system")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `system child is blocked`() {
        val v = PathSafetyGuard.check("/system/lib/libc.so")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `system_ext is blocked`() {
        assertNotNull(PathSafetyGuard.check("/system_ext/priv-app"))
    }

    @Test fun `vendor is blocked`() {
        assertNotNull(PathSafetyGuard.check("/vendor/etc/permissions"))
    }

    @Test fun `proc is blocked`() {
        assertNotNull(PathSafetyGuard.check("/proc/1/status"))
    }

    @Test fun `dev is blocked`() {
        assertNotNull(PathSafetyGuard.check("/dev/null"))
    }

    @Test fun `sys is blocked`() {
        assertNotNull(PathSafetyGuard.check("/sys/class/power_supply"))
    }

    @Test fun `apex is blocked`() {
        assertNotNull(PathSafetyGuard.check("/apex/com.android.runtime"))
    }

    // ---- other-app sandbox block ----

    @Test fun `other app sandbox is blocked`() {
        val v = PathSafetyGuard.check("/data/data/com.other.app/databases/secret.db")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `data data root itself is blocked`() {
        // /data/data (no package suffix) — treated as another-app path
        val v = PathSafetyGuard.check("/data/data")
        // The guard now matches the sandbox roots themselves as well as their descendants, so
        // listing /data/user/* / /data/user_de/* is refused too (before, the check keyed on
        // "/data/data/" only — a path that a canonical path never has).
        assertNotNull(v)
    }

    // ---- empty / null ----

    @Test fun `empty path is blocked`() {
        val v = PathSafetyGuard.check("")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `null path is blocked`() {
        val v = PathSafetyGuard.check(null)
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    // ---- traversal ----

    @Test fun `dotdot prefix is blocked`() {
        val v = PathSafetyGuard.check("../etc/passwd")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `dotdot in middle is blocked`() {
        val v = PathSafetyGuard.check("/sdcard/Download/../../../system/lib")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `dotdot suffix is blocked`() {
        val v = PathSafetyGuard.check("/sdcard/Download/..")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `just dotdot is blocked`() {
        val v = PathSafetyGuard.check("..")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }
}
