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
    /**
     * Tokens the model is documented to accept, for the picker label only: `32768` renders as
     * "32K". Best-effort, and deliberately *not* read from the GGUF - EmbeddingGemma 2's metadata
     * claims 262 144 while the model's real window is 8K, so the header is not the authority here.
     *
     * It is not what bounds an embedding call either: the runtime context is
     * [LlamaCppEmbedder.DEFAULT_CONTEXT_TOKENS] and stays put across models, because this app
     * embeds chunks rather than whole documents. A long-context model is worth choosing for how it
     * treats a chunk, not for feeding it a whole file.
     *
     * Required rather than defaulted, because a curated entry always has a documented window and
     * the picker prints it - "639 MB · 1024-dim · 32K context" is how a user tells Qwen3-Embedding
     * apart from a 512-token model at a glance.
     */
    val contextTokens: Int,
    /**
     * Text prepended to *documents* (the things that get indexed) before embedding.
     *
     * Most modern embedding models are asymmetric: a stored passage and the query that should
     * find it are embedded differently, and the model card says so. Leaving the prefix out does
     * not fail - it quietly returns vectors from the untrained side of the contrastive pair, so
     * the damage is a worse ranking and no error anywhere. That is why this is a field on the
     * curated entry rather than something a caller remembers to add.
     *
     * Empty for models trained symmetrically (EmbeddingGemma 2, BGE-M3), which is most of them.
     */
    val documentPrefix: String = "",
    /**
     * Text prepended to *queries* before embedding. See [documentPrefix] for why this exists.
     *
     * Not necessarily the same as [documentPrefix], and for the models that need one at all it
     * is usually the query that carries it: BGE models want a retrieval instruction on the
     * query only, and the `e5` / `nomic` families want a short `query:`/`search_query:` marker
     * on one side and the matching marker on the other.
     */
    val queryPrefix: String = "",
    val tags: List<String> = emptyList(),
) {
    /** Pre-built download URL, the same `resolve` shape `ModelInstall` normalises and
     *  validates for the chat models. */
    fun resolveUrl(): String = "https://huggingface.co/$repo/resolve/main/$file"

    /**
     * [contextTokens] as the picker shows it: `8K`, `32K`, or the raw count when it is not a round
     * number of thousands.
     */
    val contextLabel: String
        get() = if (contextTokens > 0 && contextTokens % 1024 == 0) {
            "${contextTokens / 1024}K"
        } else {
            contextTokens.toString()
        }
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
     *
     * The order is the recommendation order: the first entry is what an install with no explicit
     * choice embeds with (see [me.rerere.rikkahub.data.vector.EmbeddingModelRules.pick]).
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
            contextTokens = 8192,
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
            contextTokens = 8192,
            tags = listOf("multilingual", "small"),
        ),
        LlamaCppEmbeddingEntry(
            displayName = "Qwen3-Embedding 0.6B",
            // The long-context, broadly multilingual option. 32k tokens of input, 100+ languages,
            // and the strongest Chinese retrieval of anything here that a phone can hold. Last-token
            // pooling, not mean: the runtime already takes the rule from the GGUF rather than
            // assuming one, which is what makes this entry possible at all.
            repo = "Qwen/Qwen3-Embedding-0.6B-GGUF",
            file = "Qwen3-Embedding-0.6B-Q8_0.gguf",
            sizeBytes = 639_150_592L,
            dim = 1024,
            pooling = "last",
            minMemGb = 4,
            contextTokens = 32768,
            // Qwen3-Embedding is asymmetric: the model card's retrieval instruction belongs on the
            // query, and the passage is embedded bare. Without it the model still works - it just
            // answers from the side of the pair it was not trained to search with.
            queryPrefix = "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: ",
            tags = listOf("multilingual", "long-context", "chinese"),
        ),
        LlamaCppEmbeddingEntry(
            displayName = "Qwen3-Embedding 0.6B · Q4",
            // Same model as the entry above, requantised 243 MB smaller - worth having on a device
            // tight on storage. It is a *different file*, so it is a different index identity: the
            // vectors are 1024-wide either way, but the numbers differ, and switching between the
            // two is a model swap like any other (which the index already handles by rebuilding).
            repo = "mradermacher/Qwen3-Embedding-0.6B-GGUF",
            file = "Qwen3-Embedding-0.6B.Q4_K_M.gguf",
            sizeBytes = 396_475_040L,
            dim = 1024,
            pooling = "last",
            minMemGb = 4,
            contextTokens = 32768,
            queryPrefix = "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: ",
            tags = listOf("multilingual", "long-context", "chinese", "small"),
        ),
    )

    /**
     * The curated entry that installs [fileName], or null when it is not one of ours.
     *
     * The picker offers whatever GGUF is in the models directory, so a file can be curated, or
     * hand-copied, or a chat model the user pointed at by mistake. Only the curated ones carry
     * task prefixes; an unknown file gets none, which is the honest default - inventing a prefix
     * for a model we know nothing about would change what it retrieves for no stated reason.
     */
    fun entryFor(fileName: String): LlamaCppEmbeddingEntry? =
        ENTRIES.firstOrNull { it.file == fileName }
}
