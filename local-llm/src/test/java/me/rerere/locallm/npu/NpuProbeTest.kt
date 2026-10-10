package me.rerere.locallm.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Where the probe looks for its trigger.
 *
 * This is the bug that actually bit: the proot workspace binds `/workspace` to
 * `<filesDir>/workspaces/<uuid>/files`, not to `<filesDir>`, so a file dropped from inside
 * the workspace lands one level down and the app never sees it if the probe only checks the
 * top level. Both places have to be searched.
 */
class NpuProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun requestIn(dir: File): File =
        File(dir, NpuProbe.REQUEST_FILE).apply {
            parentFile?.mkdirs()
            writeText("/model\n/runtime\n")
        }

    @Test
    fun `request in the app files dir is found`() {
        val filesDir = tmp.newFolder("files")
        val r = requestIn(filesDir)
        assertEquals(r.absolutePath, NpuProbe.findRequest(filesDir)?.absolutePath)
    }

    @Test
    fun `request in a workspace files dir is found`() {
        // <filesDir>/workspaces/<uuid>/files -- what /workspace maps to inside the rootfs.
        val filesDir = tmp.newFolder("files")
        val ws = File(File(File(filesDir, "workspaces"), "5bf02aed-1ecc-4d7e-b28e-a3fd9cfcfbc9"), "files")
        val r = requestIn(ws)
        assertEquals(r.absolutePath, NpuProbe.findRequest(filesDir)?.absolutePath)
    }

    @Test
    fun `the app files dir wins over a workspace one`() {
        // Deterministic on purpose: a stale trigger in a workspace must not shadow the one an
        // app-side caller just wrote.
        val filesDir = tmp.newFolder("files")
        val top = requestIn(filesDir)
        requestIn(File(File(File(filesDir, "workspaces"), "aaa"), "files"))
        assertEquals(top.absolutePath, NpuProbe.findRequest(filesDir)?.absolutePath)
    }

    @Test
    fun `no request anywhere means no probe`() {
        val filesDir = tmp.newFolder("files")
        File(filesDir, "workspaces/some-uuid/files").mkdirs()
        assertNull(NpuProbe.findRequest(filesDir))
    }

    @Test
    fun `an unrelated file in a workspace files dir is ignored`() {
        val filesDir = tmp.newFolder("files")
        val ws = File(File(File(filesDir, "workspaces"), "bbb"), "files")
        ws.mkdirs()
        File(ws, "notes.txt").writeText("hello")
        assertNull(NpuProbe.findRequest(filesDir))
    }

    @Test
    fun `a directory named like the request is not a request`() {
        val filesDir = tmp.newFolder("files")
        File(filesDir, NpuProbe.REQUEST_FILE).mkdirs()
        assertNull(NpuProbe.findRequest(filesDir))
    }

    @Test
    fun `result file name is distinct from the request name`() {
        assertSame(false, NpuProbe.RESULT_FILE == NpuProbe.REQUEST_FILE)
    }
}
