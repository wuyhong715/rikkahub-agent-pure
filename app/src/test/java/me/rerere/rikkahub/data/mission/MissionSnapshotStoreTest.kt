package me.rerere.rikkahub.data.mission

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 F — 轮次快照：拍、列、回退、上限、保留策略。
 *
 * 全部跑在真实临时目录上（不涉及 Android），因为这一层的正确性就是文件操作的正确性。
 */
class MissionSnapshotStoreTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    private fun workspace(vararg files: Pair<String, String>): File {
        val dir = tempDir("ws")
        // 快照只拷贝 FILES 区，所以源目录本身就是「files 目录」
        for ((path, content) in files) {
            val file = File(dir, path)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        return dir
    }

    @Test
    fun `capture copies files and records metadata`() = runBlocking {
        val base = tempDir("snap")
        val store = MissionSnapshotStore(base, clock = { 1_000L })
        val files = workspace("a.md" to "A", "sub/b.txt" to "BB")

        val result = store.capture(files, "m1")

        assertTrue(result is MissionSnapshotStore.CaptureResult.Saved)
        val snapshot = (result as MissionSnapshotStore.CaptureResult.Saved).snapshot
        assertEquals(1, snapshot.round)
        assertEquals(2, snapshot.fileCount)
        assertEquals(3L, snapshot.totalBytes)
        assertEquals("round_start", snapshot.note)
        assertEquals("A", File(snapshot.dir, "a.md").readText())
        assertEquals("BB", File(snapshot.dir, "sub/b.txt").readText())

        val listed = store.list("m1")
        assertEquals(listOf(snapshot.id), listed.map { it.id })
    }

    // ------------------------------------------------------------- 符号链接（P1-16）

    @Test
    fun `capture ignores symlinks instead of following them`() = runBlocking {
        val store = MissionSnapshotStore(tempDir("snap"), clock = { 1_000L })
        val files = workspace("a.md" to "A")
        val outside = tempDir("outside")
        File(outside, "secret.txt").writeText("SECRET")
        // Exactly what `npm install` / `python -m venv` / `ln -s` leave in a workspace.
        Files.createSymbolicLink(File(files, "leak.txt").toPath(), File(outside, "secret.txt").toPath())
        Files.createSymbolicLink(File(files, "leakdir").toPath(), outside.toPath())

        val result = store.capture(files, "m1")

        assertTrue(result is MissionSnapshotStore.CaptureResult.Saved)
        val snapshot = (result as MissionSnapshotStore.CaptureResult.Saved).snapshot
        assertEquals("a symlink is not workspace data", 1, snapshot.fileCount)
        assertFalse(File(snapshot.dir, "leak.txt").exists())
        assertFalse(File(snapshot.dir, "leakdir").exists())
    }

    @Test
    fun `restore deletes a link as a link and never touches its target`() = runBlocking {
        val store = MissionSnapshotStore(tempDir("snap"), clock = { 1_000L })
        val files = workspace("a.md" to "A")
        val saved = store.capture(files, "m1")
        assertTrue(saved is MissionSnapshotStore.CaptureResult.Saved)
        val outside = tempDir("outside")
        val keep = File(outside, "keep.txt")
        keep.writeText("KEEP")
        // The workspace gained a link to an unrelated directory after the snapshot was taken.
        Files.createSymbolicLink(File(files, "link").toPath(), outside.toPath())

        val restored = store.restore(files, (saved as MissionSnapshotStore.CaptureResult.Saved).snapshot)

        assertTrue(restored is MissionSnapshotStore.RestoreResult.Restored)
        assertTrue("the link's target must survive a restore", keep.exists())
        assertEquals("KEEP", keep.readText())
        assertEquals("A", File(files, "a.md").readText())
    }

    @Test
    fun `capture of an empty workspace is not an error`() = runBlocking {
        val store = MissionSnapshotStore(tempDir("snap"))
        val result = store.capture(tempDir("ws"), "m1")
        assertTrue(result is MissionSnapshotStore.CaptureResult.Empty)
        assertTrue(store.list("m1").isEmpty())
    }

    @Test
    fun `capture skips a workspace over the byte cap`() = runBlocking {
        val base = tempDir("snap")
        val store = MissionSnapshotStore(base, maxTotalBytes = 4)
        val files = workspace("big.bin" to "0123456789")

        val result = store.capture(files, "m1")

        assertTrue(result is MissionSnapshotStore.CaptureResult.Skipped)
        assertTrue(store.list("m1").isEmpty())
        // 没有留下半份目录
        val missionDir = File(base, "m1")
        assertFalse(missionDir.exists() && missionDir.listFiles()?.isNotEmpty() == true)
    }

    @Test
    fun `capture skips when there are too many files`() = runBlocking {
        val store = MissionSnapshotStore(tempDir("snap"), maxFiles = 2)
        val files = workspace("a" to "1", "b" to "2", "c" to "3")

        val result = store.capture(files, "m1")

        assertTrue(result is MissionSnapshotStore.CaptureResult.Skipped)
    }

    @Test
    fun `round numbers increment and retention prunes old snapshots`() = runBlocking {
        var now = 1_000L
        val store = MissionSnapshotStore(tempDir("snap"), clock = { now }, keep = 2)
        val files = workspace("a.md" to "v1")

        store.capture(files, "m1")
        now = 2_000L
        store.capture(files, "m1")
        now = 3_000L
        store.capture(files, "m1")

        val listed = store.list("m1")
        assertEquals(2, listed.size)
        assertEquals(listOf(3, 2), listed.map { it.round })
        // 被剪掉的那份目录也必须删掉，否则磁盘只增不减
        assertFalse(File(listed.last().dir.replace("-2000", "-1000")).exists())
    }

    @Test
    fun `restore replaces the workspace and keeps a safety copy`() = runBlocking {
        val store = MissionSnapshotStore(tempDir("snap"))
        val files = workspace("keep.md" to "old")

        val captured = store.capture(files, "m1") as MissionSnapshotStore.CaptureResult.Saved

        // 一轮跑完：改了旧文件、加了新文件
        File(files, "keep.md").writeText("new")
        File(files, "added.md").writeText("added")

        val restored = store.restore(files, captured.snapshot)

        assertTrue(restored is MissionSnapshotStore.RestoreResult.Restored)
        assertEquals("old", File(files, "keep.md").readText())
        assertFalse("该轮新增的文件应被移除", File(files, "added.md").exists())

        val listed = store.list("m1")
        assertEquals(2, listed.size)
        val safety = listed.first { it.isSafetyCopy }
        assertTrue(File(safety.dir, "added.md").exists())
        assertTrue(File(safety.dir, "keep.md").readText() == "new")
    }

    @Test
    fun `restore of a missing snapshot directory fails cleanly`() = runBlocking {
        val store = MissionSnapshotStore(tempDir("snap"))
        val files = workspace("a.md" to "A")
        val captured = store.capture(files, "m1") as MissionSnapshotStore.CaptureResult.Saved
        File(captured.snapshot.dir).deleteRecursively()

        val result = store.restore(files, captured.snapshot)

        assertTrue(result is MissionSnapshotStore.RestoreResult.Failed)
        // 工作区没有被清空
        assertEquals("A", File(files, "a.md").readText())
    }

    @Test
    fun `purge removes the mission snapshot directory`() = runBlocking {
        val base = tempDir("snap")
        val store = MissionSnapshotStore(base)
        val files = workspace("a.md" to "A")
        store.capture(files, "m1")

        assertTrue(store.purge("m1"))
        assertFalse(File(base, "m1").exists())
        assertTrue(store.list("m1").isEmpty())
    }
}
