package me.rerere.rikkahub.data.ai.tools

import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCompositeToolsTest {

    private fun entry(name: String, dir: Boolean = false) = WorkspaceFileEntry(
        path = name,
        name = name,
        isDirectory = dir,
        sizeBytes = 0,
        updatedAt = 0,
    )

    @Test
    fun `gradle projects verify through the wrapper when it exists`() {
        assertEquals(
            "./gradlew test --console=plain",
            inferVerificationCommand(listOf(entry("build.gradle.kts"), entry("gradlew"))),
        )
        assertEquals(
            "gradle test --console=plain",
            inferVerificationCommand(listOf(entry("build.gradle"), entry("gradle"))),
        )
    }

    @Test
    fun `each ecosystem gets its own command`() {
        assertEquals("npm test", inferVerificationCommand(listOf(entry("package.json"))))
        assertEquals("cargo test", inferVerificationCommand(listOf(entry("Cargo.toml"))))
        assertEquals("go test ./...", inferVerificationCommand(listOf(entry("go.mod"))))
        assertEquals("mvn -q test", inferVerificationCommand(listOf(entry("pom.xml"))))
        assertEquals(
            "python -m pytest -q",
            inferVerificationCommand(listOf(entry("pyproject.toml"))),
        )
    }

    @Test
    fun `an unknown directory yields no command instead of a guess`() {
        assertNull(inferVerificationCommand(listOf(entry("notes.txt"), entry("src", dir = true))))
    }

    @Test
    fun `project type follows the manifest`() {
        assertEquals("gradle", detectProjectType(listOf(entry("settings.gradle.kts"))))
        assertEquals("node", detectProjectType(listOf(entry("package.json"))))
        assertEquals("rust", detectProjectType(listOf(entry("Cargo.toml"))))
        assertEquals("unknown", detectProjectType(listOf(entry("README.md"))))
    }

    @Test
    fun `failure lines keep only what a model can act on`() {
        val output = """
            > Task :app:compileDebugKotlin
            e: file.kt:12:5 Unresolved reference: foo
            BUILD FAILED in 3s
            error: 2 tests failed
            all good otherwise
        """.trimIndent()

        val lines = failureLines(output)
        assertTrue(lines.any { it.contains("Unresolved reference") })
        assertTrue(lines.any { it.contains("BUILD FAILED") })
        assertTrue(lines.any { it.contains("2 tests failed") })
        assertEquals(3, lines.size)
    }

    @Test
    fun `failure lines are capped`() {
        val output = (1..40).joinToString("\n") { "error number $it" }
        assertEquals(12, failureLines(output).size)
    }

    @Test
    fun `tail keeps the end and marks truncation`() {
        val long = "a".repeat(5_000)
        val tail = tailOf(long, max = 100)
        assertTrue(tail.startsWith("…"))
        assertEquals(102, tail.length)

        assertEquals("short", tailOf("short", max = 100))
    }
}
