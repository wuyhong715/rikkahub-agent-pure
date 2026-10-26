package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「所有工作区」的核心其实只有这一个纯函数：模型给的 `workspace` 参数怎么落到具体 id。
 *
 * 两条容易写错的规则：单工作区助手要**忽略**这个参数（不能因为模型多写一个就报错），
 * 以及名字要大小写不敏感（用户给工作区起名 "Moxw" 时模型很可能写 "moxw"）。
 */
class WorkspaceTargetsTest {

    private val moxw = WorkspaceTargets.Option(id = "11111111-aaaa", name = "Moxw", root = "moxw")
    private val notes = WorkspaceTargets.Option(id = "22222222-bbbb", name = "笔记", root = "notes")

    @Test
    fun `a single-workspace assistant ignores the argument entirely`() {
        val targets = WorkspaceTargets(primaryId = moxw.id, options = emptyList())
        assertFalse(targets.multi)
        assertEquals(moxw.id, targets.resolveOrNull(null))
        // 模型幻觉出一个 workspace 参数时不该失败——单工作区就是那一个。
        assertEquals(moxw.id, targets.resolveOrNull("whatever"))
    }

    @Test
    fun `multi mode resolves by name case-insensitively, by id and by root`() {
        val targets = WorkspaceTargets(primaryId = moxw.id, options = listOf(moxw, notes))
        assertTrue(targets.multi)
        assertEquals(notes.id, targets.resolveOrNull("笔记"))
        assertEquals(notes.id, targets.resolveOrNull("notes"))
        assertEquals(moxw.id, targets.resolveOrNull("moxw"))
        assertEquals(notes.id, targets.resolveOrNull(notes.id))
    }

    @Test
    fun `omitting the argument falls back to the primary workspace`() {
        val targets = WorkspaceTargets(primaryId = notes.id, options = listOf(moxw, notes))
        assertEquals(notes.id, targets.resolveOrNull(null))
        assertEquals(notes.id, targets.resolveOrNull("   "))
    }

    @Test
    fun `an unknown name resolves to null so the tool can explain instead of guessing`() {
        val targets = WorkspaceTargets(primaryId = moxw.id, options = listOf(moxw, notes))
        assertNull(targets.resolveOrNull("does-not-exist"))
    }

    @Test
    fun `the description lists every option so the model can pick without another call`() {
        val targets = WorkspaceTargets(primaryId = moxw.id, options = listOf(moxw, notes))
        val described = targets.describe()
        assertTrue(described.contains("Moxw"))
        assertTrue(described.contains("笔记"))
    }
}
