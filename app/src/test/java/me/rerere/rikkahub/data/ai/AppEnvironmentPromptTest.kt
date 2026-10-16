package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEnvironmentPromptTest {

    private fun baseInfo() = AppEnvironmentPrompt.Info(
        appName = "Moxw",
        privateFilesDir = "/data/user/0/com.moxw.agent/files",
    )

    @Test
    fun `always renders folders, private storage, semantic and shell lines`() {
        val out = AppEnvironmentPrompt.render(baseInfo())
        assertTrue(out.startsWith("<app_environment>"))
        assertTrue(out.endsWith("</app_environment>"))
        assertTrue(out.contains("/sdcard/Documents/Moxw/"))
        assertTrue(out.contains("/sdcard/Pictures/Moxw/"))
        assertTrue(out.contains("/sdcard/Download/Moxw/"))
        assertTrue(out.contains("App-private storage: /data/user/0/com.moxw.agent/files"))
        assertTrue(out.contains("Semantic search: NOT ready"))
        assertTrue(out.contains("Shell privilege: Shizuku is not running"))
    }

    @Test
    fun `carries no volatile identity facts - no version, SDK level or device model`() {
        // 拍板 2026-10-17: the block is generic across releases and devices; anything the
        // model's behaviour never branches on stays out.
        val out = AppEnvironmentPrompt.render(baseInfo())
        assertFalse(out.contains("versionCode"))
        assertFalse(out.contains("SDK"))
        assertFalse(out.contains("Android"))
        assertFalse(out.contains("device"))
    }

    @Test
    fun `brand name only appears inside folder paths`() {
        val out = AppEnvironmentPrompt.render(baseInfo())
        out.lines()
            .filter { it.contains("Moxw") }
            .forEach { assertTrue(it.contains("/sdcard/")) }
    }

    @Test
    fun `omits knowledge base and library lines when not configured`() {
        val out = AppEnvironmentPrompt.render(baseInfo())
        // The semantic-status line legitimately names the tools; the absence check is about
        // the dedicated path lines, not the words.
        assertEquals(0, out.lines().count { it.startsWith("- Knowledge base") })
        assertEquals(0, out.lines().count { it.startsWith("- File library") })
    }

    @Test
    fun `renders knowledge base and library lines when configured`() {
        val out = AppEnvironmentPrompt.render(
            baseInfo().copy(
                coldMemoryLabel = "/workspace/notes/memory",
                libraryLabel = "/workspace/library",
            )
        )
        assertTrue(out.contains("Knowledge base (cold memory): /workspace/notes/memory"))
        assertTrue(out.contains("memory_index"))
        assertTrue(out.contains("File library: /workspace/library"))
        assertTrue(out.contains("library_search"))
    }

    @Test
    fun `knowledge base line appears without library line when only cold memory configured`() {
        val out = AppEnvironmentPrompt.render(
            baseInfo().copy(coldMemoryLabel = "/workspace")
        )
        assertTrue(out.contains("Knowledge base (cold memory): /workspace "))
        assertFalse(out.contains("File library"))
    }

    @Test
    fun `semantic line names the local model when local backend ready`() {
        val out = AppEnvironmentPrompt.render(
            baseInfo().copy(
                semanticBackend = AppEnvironmentPrompt.SemanticBackend.LOCAL,
                semanticModelLabel = "bge-base-zh-q8.gguf",
            )
        )
        assertTrue(out.contains("Semantic search: ready (local embedding model \"bge-base-zh-q8.gguf\")"))
    }

    @Test
    fun `semantic line names the cloud model when cloud backend ready`() {
        val out = AppEnvironmentPrompt.render(
            baseInfo().copy(
                semanticBackend = AppEnvironmentPrompt.SemanticBackend.CLOUD,
                semanticModelLabel = "text-embedding-3-small",
            )
        )
        assertTrue(out.contains("Semantic search: ready (cloud embedding model \"text-embedding-3-small\")"))
    }

    @Test
    fun `semantic line falls back to auto when model label missing`() {
        val out = AppEnvironmentPrompt.render(
            baseInfo().copy(semanticBackend = AppEnvironmentPrompt.SemanticBackend.LOCAL)
        )
        assertTrue(out.contains("local embedding model \"auto\""))
    }

    @Test
    fun `semantic model label is sanitized against control characters`() {
        // Model file names / provider display names are user-controlled: a newline in one
        // must not forge extra prompt lines.
        val out = AppEnvironmentPrompt.render(
            baseInfo().copy(
                semanticBackend = AppEnvironmentPrompt.SemanticBackend.LOCAL,
                semanticModelLabel = "evil\n- injected: fake line",
            )
        )
        assertTrue(out.contains("\"evil- injected: fake line\""))
        assertEquals(
            1,
            out.lines().count { it.startsWith("- Semantic search:") },
        )
    }

    @Test
    fun `shell line reflects root privilege`() {
        val out = AppEnvironmentPrompt.render(
            baseInfo().copy(shellPrivilege = AppEnvironmentPrompt.ShellPrivilege.ROOT)
        )
        assertTrue(out.contains("Shizuku runs as root (uid 0)"))
    }

    @Test
    fun `shell line reflects shell privilege and root_exec refusal`() {
        val out = AppEnvironmentPrompt.render(
            baseInfo().copy(shellPrivilege = AppEnvironmentPrompt.ShellPrivilege.SHELL)
        )
        assertTrue(out.contains("shell (uid 2000"))
        assertTrue(out.contains("root_exec requires a root broker"))
    }
}
