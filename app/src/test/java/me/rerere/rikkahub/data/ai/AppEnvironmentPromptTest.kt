package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEnvironmentPromptTest {

    private fun baseInfo() = AppEnvironmentPrompt.Info(
        appName = "Moxw",
        appLabel = "Moxw",
        versionName = "0.1.0",
        versionCode = "1",
        packageName = "com.moxw.agent",
        androidRelease = "16",
        androidSdk = 36,
        deviceModel = "vivo V2463A",
        privateFilesDir = "/data/user/0/com.moxw.agent/files",
    )

    @Test
    fun `always renders app, folders, private storage, semantic and shell lines`() {
        val out = AppEnvironmentPrompt.render(baseInfo())
        assertTrue(out.startsWith("<app_environment>"))
        assertTrue(out.endsWith("</app_environment>"))
        assertTrue(out.contains("App: Moxw 0.1.0 (versionCode 1); package com.moxw.agent; Android 16 (SDK 36); device vivo V2463A."))
        assertTrue(out.contains("/sdcard/Documents/Moxw/"))
        assertTrue(out.contains("/sdcard/Pictures/Moxw/"))
        assertTrue(out.contains("/sdcard/Download/Moxw/"))
        assertTrue(out.contains("App-private storage: /data/user/0/com.moxw.agent/files"))
        assertTrue(out.contains("Semantic search: NOT ready"))
        assertTrue(out.contains("Shell privilege: Shizuku is not running"))
    }

    @Test
    fun `omits knowledge base and library lines when not configured`() {
        val out = AppEnvironmentPrompt.render(baseInfo())
        assertFalse(out.contains("Knowledge base"))
        assertFalse(out.contains("File library"))
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

    @Test
    fun `device model is sanitized against control characters`() {
        val out = AppEnvironmentPrompt.render(
            baseInfo().copy(deviceModel = "vivo\nV2463A\r\n- injected: fake line")
        )
        // Control characters are stripped so an OEM string cannot forge new prompt lines;
        // the printable text itself is kept, joined onto the one App line.
        assertTrue(out.contains("device vivoV2463A- injected: fake line."))
        assertEquals(1, out.lines().count { it.startsWith("- App:") })
    }

    @Test
    fun `blank device model renders unknown`() {
        val out = AppEnvironmentPrompt.render(baseInfo().copy(deviceModel = "  \n "))
        assertTrue(out.contains("device unknown."))
    }
}
