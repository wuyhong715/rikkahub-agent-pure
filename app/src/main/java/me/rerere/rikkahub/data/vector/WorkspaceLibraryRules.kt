package me.rerere.rikkahub.data.vector

/**
 * What the file library is allowed to contain, and how much of it one round may read.
 *
 * Moxw - the library is a directory in the workspace that the user points at, and the index reads
 * it recursively. Both halves of that sentence are why this object exists: a workspace can hold
 * anything (the one this was built against holds two repository clones, a gradle cache and a pile
 * of jars), so the walk needs an allow-list rather than a deny-list, and reading it can cost
 * minutes of embedding, so one round needs a budget.
 *
 * The allow-list is by extension. That is a deliberate simplification: it means every file the
 * index touches is one whose text we can actually read, and it makes "why is this file missing"
 * answerable by looking at one set. A deny-list would have to enumerate binaries, and would get
 * the next one wrong.
 */
enum class LibraryFileKind { TEXT, CODE, DOCUMENT }

/** Which `:document` parser reads a file, if any. Kept here so the mapping is testable. */
enum class LibraryDocumentParser { PDF, DOCX, PPTX, EPUB }

object WorkspaceLibraryRules {

    /** Where the library lives until the user picks somewhere else. Relative to the workspace. */
    const val DEFAULT_DIR = "library"

    /**
     * Per-file caps. Text is capped by bytes because we read all of it; a document is capped by
     * bytes as a proxy for cost, and its *extracted* text is capped separately - a 30 MB PDF of
     * scanned images extracts to very little, while a text-heavy one extracts to more than any
     * model call wants to see.
     */
    const val MAX_TEXT_FILE_BYTES = 2_000_000L
    const val MAX_DOCUMENT_FILE_BYTES = 30_000_000L
    const val MAX_TEXT_CHARS = 400_000
    const val MAX_DOCUMENT_CHARS = 400_000

    /**
     * One round's budget. This is the number that keeps a phone from embedding for an hour: at
     * roughly 4k characters per chunk, [MAX_CHARS_PER_ROUND] is a few hundred model calls, which
     * is minutes rather than tens of minutes, and [MAX_FILES_PER_ROUND] bounds the directory
     * traffic for a library of many tiny files.
     */
    const val MAX_CHARS_PER_ROUND = 1_500_000
    const val MAX_FILES_PER_ROUND = 400

    private val DECLARED_DOCUMENT = setOf("pdf", "docx", "pptx", "epub")

    private val DECLARED_TEXT = setOf(
        "md", "markdown", "txt", "text", "rst", "org", "adoc",
        "csv", "tsv", "log", "ndjson", "jsonl",
        "json", "json5", "yaml", "yml", "toml", "ini", "conf", "cfg", "properties", "env",
        "xml", "html", "htm", "srt", "vtt", "tex", "bib", "po", "patch", "diff",
    )

    private val DECLARED_CODE = setOf(
        "kt", "kts", "java", "gradle", "groovy", "scala", "clj", "cljs", "ex", "exs", "erl", "hs", "ml",
        "py", "pyi", "rb", "php", "pl", "lua", "r", "jl", "dart", "swift", "m", "mm", "go", "rs",
        "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "cs", "fs", "vb", "asm", "s",
        "js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte", "astro",
        "sh", "bash", "zsh", "fish", "ksh", "bat", "cmd", "ps1",
        "sql", "graphql", "gql", "proto", "tf", "hcl", "cmake", "mk", "nix", "vim",
    )

    /** Extension-less files that are still worth indexing, by their conventional names. */
    private val BARE_NAMES_TEXT = setOf(
        "readme", "license", "licence", "notice", "changelog", "changes", "authors", "contributors",
        "todo", "copying", "install",
    )
    private val BARE_NAMES_CODE = setOf(
        "makefile", "dockerfile", "containerfile", "rakefile", "gemfile", "procfile", "justfile",
    )

    /**
     * Directories never walked into. Every dot-directory is excluded at once (`.git`, `.gradle`,
     * `.venv`, `.idea`, `.cxx`), which is the bulk of what makes a workspace huge, plus the
     * regenerated trees that are cheap to rebuild and expensive to embed.
     */
    private val IGNORED_DIRECTORY_NAMES = setOf(
        "node_modules", "build", "dist", "out", "target", "vendor", "venv", "env",
        "__pycache__", "coverage", "Pods", "DerivedData", "tmp", "logs",
    )

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isMarkdown(name: String): Boolean = extensionOf(name) in setOf("md", "markdown")

    /** `null` means the index never looks at this file. */
    fun kindOf(name: String): LibraryFileKind? {
        val extension = extensionOf(name)
        if (extension.isNotEmpty()) {
            return when (extension) {
                in DECLARED_DOCUMENT -> LibraryFileKind.DOCUMENT
                in DECLARED_CODE -> LibraryFileKind.CODE
                in DECLARED_TEXT -> LibraryFileKind.TEXT
                else -> null
            }
        }
        val bare = name.lowercase()
        return when {
            bare in BARE_NAMES_CODE -> LibraryFileKind.CODE
            bare in BARE_NAMES_TEXT -> LibraryFileKind.TEXT
            else -> null
        }
    }

    /** The parser that reads a document, or null when this file is not one. */
    fun documentParserFor(name: String): LibraryDocumentParser? =
        if (kindOf(name) != LibraryFileKind.DOCUMENT) {
            null
        } else {
            when (extensionOf(name)) {
                "pdf" -> LibraryDocumentParser.PDF
                "docx" -> LibraryDocumentParser.DOCX
                "pptx" -> LibraryDocumentParser.PPTX
                "epub" -> LibraryDocumentParser.EPUB
                else -> null
            }
        }

    /**
     * How a file is cut up. Code has its own mode because a function body split in half embeds as
     * two unrelated things; prose is cut at headings when it has them, at paragraphs when it
     * does not; a document is prose whatever it was authored in.
     */
    fun chunkModeOf(kind: LibraryFileKind, name: String): ChunkingMode = when (kind) {
        LibraryFileKind.CODE -> ChunkingMode.CODE
        LibraryFileKind.TEXT -> if (isMarkdown(name)) ChunkingMode.MARKDOWN else ChunkingMode.PLAIN
        LibraryFileKind.DOCUMENT -> ChunkingMode.PLAIN
    }

    fun isIgnoredDirectory(name: String): Boolean =
        name.startsWith(".") || name in IGNORED_DIRECTORY_NAMES

    /**
     * True when any segment of a relative path sits in an ignored directory. Checked per segment
     * rather than as a prefix so that `a/node_modules/b.md` is skipped as reliably as
     * `node_modules/b.md`.
     */
    fun isIgnoredPath(relativePath: String): Boolean =
        relativePath.split('/').any { it.isNotEmpty() && isIgnoredDirectory(it) }

    fun sizeCapOf(kind: LibraryFileKind): Long = when (kind) {
        LibraryFileKind.DOCUMENT -> MAX_DOCUMENT_FILE_BYTES
        else -> MAX_TEXT_FILE_BYTES
    }

    fun charCapOf(kind: LibraryFileKind): Int = when (kind) {
        LibraryFileKind.DOCUMENT -> MAX_DOCUMENT_CHARS
        else -> MAX_TEXT_CHARS
    }

    /**
     * Roughly how much text a file will produce, used to decide whether there is room for it in
     * this round without reading it first.
     *
     * For text the byte count *is* the estimate (UTF-8, and an over-estimate for non-Latin scripts
     * only makes the budget more conservative). For a document the ratio is a guess - four bytes
     * of PDF per character of text - and it is a guess on purpose: the alternative is parsing
     * every file to find out it does not fit. Being wrong costs one round's budget, not
     * correctness.
     */
    fun estimatedChars(kind: LibraryFileKind, sizeBytes: Long): Int = when (kind) {
        LibraryFileKind.DOCUMENT ->
            (sizeBytes / 4).coerceAtMost(MAX_DOCUMENT_CHARS.toLong()).toInt()
        else ->
            sizeBytes.coerceAtMost(MAX_TEXT_CHARS.toLong()).toInt()
    }

    /**
     * Which indexed documents a round should forget.
     *
     * [seen] is everything the walk mentioned - read this round or not. Only a document that is
     * absent from it is taken as deleted, which is the only evidence of deletion available. This
     * is the rule that stops a round with a budget from deleting the previous round's work: the
     * first version of the sync treated "not read this round" as "gone", and would have re-embeds
     * the same prefix forever while never keeping the rest. It lives here, tested, rather than
     * inline in the sync where it was easy to get wrong twice.
     */
    fun toForget(indexed: Collection<String>, seen: Collection<String>): List<String> =
        indexed.filter { it !in seen }

    /** One file the walk found and the allow-list accepted. */
    data class Candidate(
        /** Workspace-absolute path, the form every other tool takes. */
        val path: String,
        val name: String,
        val kind: LibraryFileKind,
        val sizeBytes: Long,
    )

    data class Round(
        val take: List<Candidate>,
        /** Accepted files that did not fit this round's budget. */
        val deferred: Int,
        /** Files refused by a size cap. Reported so a missing file can be explained. */
        val tooBig: List<String>,
    )

    /**
     * Chooses what to read this round.
     *
     * Files the index has never seen come first, then everything else in path order. That
     * ordering is what lets a library bigger than one round's budget converge: the second round
     * picks up where the first stopped instead of re-reading the same prefix forever, and a
     * library that fits in one round is read in a stable order.
     */
    fun planRound(
        candidates: List<Candidate>,
        indexed: Set<String>,
        budgetChars: Int = MAX_CHARS_PER_ROUND,
        maxFiles: Int = MAX_FILES_PER_ROUND,
    ): Round {
        val tooBig = candidates
            .filter { it.sizeBytes > sizeCapOf(it.kind) }
            .map { it.path }
        val eligible = candidates
            .filter { it.sizeBytes <= sizeCapOf(it.kind) }
            .sortedWith(compareBy({ it.path in indexed }, { it.path }))

        val take = mutableListOf<Candidate>()
        var chars = 0L
        for (candidate in eligible) {
            if (take.size >= maxFiles) break
            val cost = estimatedChars(candidate.kind, candidate.sizeBytes).toLong()
            // Always take at least one file: a round that plans nothing because the first file is
            // large would never make progress on a library of large files.
            if (take.isNotEmpty() && chars + cost > budgetChars) break
            take += candidate
            chars += cost
        }
        return Round(take = take, deferred = eligible.size - take.size, tooBig = tooBig)
    }
}
