package me.rerere.rikkahub.data.vector

import java.io.File
import me.rerere.locallm.LocalRuntime
import me.rerere.locallm.ModelInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The directory the embedding lookup reads has to be the directory the installer writes to.
 *
 * It was not: the download and the SAF import both wrote into `local-models/llamacpp/`, while the
 * lookup listed `local-models/` itself - which holds only the runtime subdirectories and therefore
 * never a `.gguf`. Everything compiled, every other test passed, and semantic search was silently
 * unavailable no matter what the user installed. These are the tests that would have caught it.
 */
class EmbeddingModelFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun base(): File = temp.newFolder("local-models")

    @Test
    fun `the lookup reads the directory a download writes into`() {
        val base = base()
        val written = ModelInstall.targetFile(base, LocalRuntime.LlamaCpp, "embeddinggemma-2-Q8_0.gguf")

        assertEquals(written.parentFile, EmbeddingModelFiles.dir(base))
    }

    @Test
    fun `a downloaded model is found by the lookup`() {
        val base = base()
        val written = ModelInstall.targetFile(base, LocalRuntime.LlamaCpp, "embeddinggemma-2-Q8_0.gguf")
        written.parentFile?.mkdirs()
        written.writeText("not really a gguf, but a file")

        assertEquals(listOf("embeddinggemma-2-Q8_0.gguf"), EmbeddingModelFiles.installedFiles(base))
    }

    @Test
    fun `a gguf dropped in the root is not mistaken for an installed model`() {
        // The old bug in reverse: a file in the root was what the lookup looked for, and it is
        // not where anything installs to.
        val base = base()
        File(base, "dropped-by-hand.gguf").writeText("x")

        assertTrue(EmbeddingModelFiles.installedFiles(base).isEmpty())
    }

    @Test
    fun `only model files count, and the list is sorted`() {
        val base = base()
        val dir = EmbeddingModelFiles.dir(base)
        File(dir, "b-model.gguf").writeText("x")
        File(dir, "a-model.gguf").writeText("x")
        File(dir, "c-model.litertlm").writeText("x")
        File(dir, "notes.txt").writeText("x")
        File(dir, "subdir").mkdirs()

        assertEquals(
            listOf("a-model.gguf", "b-model.gguf", "c-model.litertlm"),
            EmbeddingModelFiles.installedFiles(base),
        )
    }

    @Test
    fun `a litertlm lives in its own directory and is found there`() {
        val base = base()
        val written = ModelInstall.targetFile(
            base,
            LocalRuntime.LiteRT,
            "embeddinggemma-2-text-vision-440m.litertlm",
        )
        written.parentFile?.mkdirs()
        written.writeText("not really a litertlm, but a file")

        // The two runtimes must not share a directory: a GGUF and a .litertlm are looked up by
        // name across both, and a download writing one into the other's directory is the kind of
        // thing that only shows up as "the model I installed is not there".
        assertNotEquals(EmbeddingModelFiles.dir(base), EmbeddingModelFiles.litertDir(base))
        assertEquals(written.parentFile, EmbeddingModelFiles.litertDir(base))
        assertEquals(
            listOf("embeddinggemma-2-text-vision-440m.litertlm"),
            EmbeddingModelFiles.installedFiles(base),
        )
        assertEquals(
            written,
            EmbeddingModelFiles.fileFor(base, "embeddinggemma-2-text-vision-440m.litertlm"),
        )
    }

    @Test
    fun `both runtimes are listed together, and fileFor finds either`() {
        val base = base()
        val gguf = ModelInstall.targetFile(base, LocalRuntime.LlamaCpp, "a.gguf")
        gguf.parentFile?.mkdirs()
        gguf.writeText("x")
        val litert = ModelInstall.targetFile(base, LocalRuntime.LiteRT, "b.litertlm")
        litert.parentFile?.mkdirs()
        litert.writeText("x")

        assertEquals(listOf("a.gguf", "b.litertlm"), EmbeddingModelFiles.installedFiles(base))
        assertEquals(gguf, EmbeddingModelFiles.fileFor(base, "a.gguf"))
        assertEquals(litert, EmbeddingModelFiles.fileFor(base, "b.litertlm"))
        assertNull(EmbeddingModelFiles.fileFor(base, "missing.gguf"))
    }

    @Test
    fun `an empty directory is empty rather than an error`() {
        assertTrue(EmbeddingModelFiles.installedFiles(temp.newFolder("empty")).isEmpty())
    }
}
