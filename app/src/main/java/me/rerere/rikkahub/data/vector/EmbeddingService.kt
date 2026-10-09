package me.rerere.rikkahub.data.vector

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.llamacpp.LlamaCppEmbedder
import me.rerere.llamacpp.LlamaCppEmbeddingCatalog
import me.rerere.llamacpp.ModelTooLargeException
import java.io.File

/**
 * The app's one door to the embedding model.
 *
 * One model at a time, on purpose: it is a few hundred megabytes resident and a device has one
 * pair of hands. Everything that wants a vector - the memory index today, tools and files later -
 * comes through here, so the "which model, is it loaded, what width is it" questions have exactly
 * one answer, and a model swap invalidates the index in one place rather than in each caller.
 *
 * The model is resolved from what is installed rather than from a baked-in choice: the file names
 * in [LlamaCppEmbeddingCatalog] are what the ordinary local-model page writes into
 * `local-models/llamacpp`, so installing the recommended GGUF is all it takes. See
 * [EmbeddingModelRules.pick] for how an explicit setting and that fallback interact.
 */
class EmbeddingService(
    /** Where installed GGUF files live; a lambda so tests never touch the filesystem. */
    private val modelsDir: () -> File,
    /** The user's explicit choice, if any. */
    private val configuredFileName: suspend () -> String?,
    private val embedder: LlamaCppEmbedder = LlamaCppEmbedder(),
    private val curatedOrder: List<String> = LlamaCppEmbeddingCatalog.ENTRIES.map { it.file },
) {

    /** The loaded model: what it is, and how wide its vectors are. */
    data class ActiveModel(val fileName: String, val dim: Int) {
        /** What goes into `vector_chunks.model_id`. */
        val modelId: String get() = EmbeddingModelRules.modelIdOf(fileName)
    }

    private val lock = Mutex()

    @Volatile
    private var active: ActiveModel? = null

    /** The model in use, or null when none is installed. Loading is lazy and happens once. */
    val current: ActiveModel? get() = active

    /**
     * Loads the model if it is not loaded yet. Returns null when there is nothing usable to
     * embed with, which callers treat as "semantic search is off", not as an error.
     *
     * A model that fails to load is *not* remembered as failed: the next call tries again, so
     * freeing memory or re-downloading a truncated file fixes the situation without a restart.
     */
    suspend fun ensureLoaded(): ActiveModel? = lock.withLock {
        active?.let { return@withLock it }

        val installed = modelsDir().listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
        val fileName = EmbeddingModelRules.pick(
            configured = configuredFileName(),
            installed = installed,
            curatedOrder = curatedOrder,
        ) ?: return@withLock null

        val file = File(modelsDir(), fileName)
        if (!file.isFile) return@withLock null
        val info = embedder.load(file.absolutePath)
        val loaded = ActiveModel(fileName = fileName, dim = info.dim)
        active = loaded
        loaded
    }

    /**
     * Embeds [texts] in order. Throws when no model is available - callers that can live without
     * embeddings check [ensureLoaded] first and skip instead.
     */
    suspend fun embed(texts: List<String>): List<FloatArray> {
        val model = ensureLoaded() ?: error("no embedding model is installed")
        return texts.map { text ->
            val vector = embedder.embedNormalized(text)
            require(vector.size == model.dim) {
                "the model returned ${vector.size} values, expected ${model.dim}"
            }
            vector
        }
    }

    suspend fun embedOne(text: String): FloatArray = embed(listOf(text)).first()

    /**
     * Frees the model. Called when the index has just been rebuilt - holding a few hundred
     * megabytes of idle weights through a phone's whole idle period is exactly the kind of thing
     * that makes a background feature feel expensive.
     */
    suspend fun release() = lock.withLock {
        if (active != null) {
            embedder.unload()
            active = null
        }
    }

    /** True when a model is installed and usable. Never throws. */
    suspend fun isAvailable(): Boolean = try {
        ensureLoaded() != null
    } catch (e: ModelTooLargeException) {
        // Installed but it does not fit in this device's memory: an answer, not a crash.
        false
    }
}
