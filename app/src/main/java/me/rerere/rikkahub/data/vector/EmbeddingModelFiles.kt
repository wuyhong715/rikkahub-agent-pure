package me.rerere.rikkahub.data.vector

import android.content.Context
import java.io.File
import me.rerere.locallm.LocalRuntime
import me.rerere.locallm.ModelInstall

/**
 * Where the embedding model files are - one answer, shared by both sides of the same coin.
 *
 * The download path writes through [ModelInstall.targetFile], the SAF import writes through it
 * too, and the embedding lookup reads, so all three have to agree on a directory. They are kept
 * in one place here rather than spelled out at each call site, because they did not agree once:
 * the lookup listed `local-models/` while everything else wrote to `local-models/llamacpp/`, so
 * an installed model was invisible and semantic search stayed silent no matter what the user did.
 *
 * Deliberately the llama.cpp directory rather than the root: the curated embedding models are
 * llama.cpp GGUFs, and everything that installs one puts it there.
 */
object EmbeddingModelFiles {

    /** The directory the embedding lookup reads, for a given base (`local-models/`). */
    fun dir(baseDir: File): File = ModelInstall.runtimeDir(baseDir, LocalRuntime.LlamaCpp)

    /** The same directory, for the app's real base directory. */
    fun dir(context: Context): File = dir(ModelInstall.localModelsDir(context))

    /** The GGUF files present, by name, sorted. Empty when the directory does not exist. */
    fun installedFiles(baseDir: File): List<String> = installedFilesIn(dir(baseDir))

    fun installedFiles(context: Context): List<String> = installedFilesIn(dir(context))

    private fun installedFilesIn(dir: File): List<String> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".gguf", ignoreCase = true) }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
}
