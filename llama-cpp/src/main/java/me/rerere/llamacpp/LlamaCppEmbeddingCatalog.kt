package me.rerere.llamacpp

/**
 * A curated GGUF the embedding-model picker offers.
 *
 * Deliberately a different shape from [LlamaCppCatalogEntry] rather than a nullable-field
 * superset of it: an embedding model has no chat template, no sampler and no thinking mode,
 * while a chat model has no pooling rule and no output width. Sharing one class would mean
 * every consumer carrying fields that are meaningless for exactly one of the two uses.
 */
data class LlamaCppEmbeddingEntry(
    val displayName: String, // e.g. "EmbeddingGemma 2"
    val repo: String,        // HuggingFace repo path, e.g. "ggml-org/embeddinggemma-2-GGUF"
    val file: String,        // File inside the repo
    val sizeBytes: Long,
    /**
     * Nominal output width, for the picker label only. The authoritative value is read from
     * the GGUF at load time (`embedding_length_out`, surfaced as [LlamaCppEmbedInfo.dim]); this
     * one is here so the UI can say "768-dim" before anything is downloaded, and a disagreement
     * between the two means the file was re-converted upstream.
     */
    val dim: Int,
    /** Nominal pooling rule, same caveat as [dim] - the GGUF's own value wins. */
    val pooling: String,
    val minMemGb: Int,
    val tags: List<String> = emptyList(),
) {
    /** Pre-built download URL, the same `resolve` shape `ModelInstall` normalises and
     *  validates for the chat models. */
    fun resolveUrl(): String = "https://huggingface.co/$repo/resolve/main/$file"
}

object LlamaCppEmbeddingCatalog {
    /**
     * The curated embedding models.
     *
     * Entry criteria, set by what the runtime can actually load rather than by taste:
     *
     *  * the GGUF must declare an architecture the pinned llama.cpp knows - EmbeddingGemma 2
     *    says `gemma-embedding2`, which llama.cpp learned in `4fbc76dec51d` ("model: support
     *    embeddinggemma2"), so the submodule pin is a hard prerequisite for this entry;
     *  * the text weights must be a single self-contained GGUF. EmbeddingGemma 2 is natively
     *    multimodal, and its vision/audio halves ship as a separate `mmproj-*.gguf` - the ones
     *    listed here are the text-only files, which is all this app embeds today;
     *  * the repo must be public and ungated.
     *
     * Every repo, file name and byte size below was read off the live HuggingFace API on
     * 2026-10-10. They are exact and must not be edited, guessed at, or extended without
     * re-verifying against the live API: a wrong repo id or file name 404s on first download,
     * which is worse than shipping no entry at all.
     */
    val ENTRIES: List<LlamaCppEmbeddingEntry> = listOf(
        LlamaCppEmbeddingEntry(
            displayName = "EmbeddingGemma 2",
            repo = "ggml-org/embeddinggemma-2-GGUF",
            file = "embeddinggemma-2-Q8_0.gguf",
            sizeBytes = 309_855_456L,
            dim = 768,
            pooling = "mean",
            minMemGb = 4,
            tags = listOf("multilingual", "recommended"),
        ),
        LlamaCppEmbeddingEntry(
            displayName = "EmbeddingGemma 2 · Q4",
            // Community requant of the same model: 134 MB smaller, which is worth having on a
            // device that is tight on storage or RAM. Same text-only GGUF, same architecture.
            repo = "unsloth/embeddinggemma-2-GGUF",
            file = "embeddinggemma-2-UD-Q4_K_XL.gguf",
            sizeBytes = 175_673_856L,
            dim = 768,
            pooling = "mean",
            minMemGb = 4,
            tags = listOf("multilingual", "small"),
        ),
    )
}
