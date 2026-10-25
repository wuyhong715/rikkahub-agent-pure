package me.rerere.rikkahub.service.mission

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 批 I — 分叉的工作区拷贝：结构、二进制、符号链接、目标非空。 */
class MissionForkCopyTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `copies nested files and keeps bytes intact`() {
        val source = temp.newFolder("src")
        File(source, "report.md").writeText("# 报告")
        File(source, "out").mkdirs()
        File(source, "out/data.bin").writeBytes(byteArrayOf(0, 1, 2, 3, -1))
        File(source, "out/nested").mkdirs()
        File(source, "out/nested/notes.txt").writeText("继续")

        val target = temp.newFolder("dst")
        val stats = MissionForkCopy.copyTree(source, target)

        assertEquals(3, stats.files)
        assertEquals("# 报告", File(target, "report.md").readText())
        assertTrue(File(target, "out/data.bin").readBytes().contentEquals(byteArrayOf(0, 1, 2, 3, -1)))
        assertEquals("继续", File(target, "out/nested/notes.txt").readText())
    }

    @Test
    fun `refuses a non empty target`() {
        val source = temp.newFolder("src2")
        File(source, "a.txt").writeText("a")
        val target = temp.newFolder("dst2")
        File(target, "existing.txt").writeText("user data")

        val failure = runCatching { MissionForkCopy.copyTree(source, target) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("用户已有内容不能被覆盖", "user data", File(target, "existing.txt").readText())
    }

    @Test
    fun `does not follow symlinks`() {
        val outside = temp.newFolder("outside")
        File(outside, "secret.txt").writeText("outside data")
        val source = temp.newFolder("src3")
        File(source, "normal.txt").writeText("ok")
        runCatching {
            java.nio.file.Files.createSymbolicLink(
                File(source, "link").toPath(),
                outside.toPath(),
            )
        }

        val target = temp.newFolder("dst3")
        val stats = MissionForkCopy.copyTree(source, target)

        assertEquals(1, stats.files)
        assertTrue(!File(target, "link/secret.txt").exists())
    }

    @Test
    fun `empty source copies nothing`() {
        val source = temp.newFolder("src4")
        val target = temp.newFolder("dst4")
        val stats = MissionForkCopy.copyTree(source, target)
        assertEquals(0, stats.files)
        assertEquals(0L, stats.bytes)
    }
}
