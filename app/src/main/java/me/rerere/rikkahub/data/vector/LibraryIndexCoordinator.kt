package me.rerere.rikkahub.data.vector

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.data.ai.tools.ColdMemoryRules
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageArea
import java.io.File

private const val TAG = "LibraryIndex"

/**
 * Keeps the file library's index up to date, in the background.
 *
 * Shaped after [MemoryIndexCoordinator] - same two triggers, same debounce, same "a sync that
 * fails is a status, not an exception" - but it is a separate class rather than a generalisation
 * of that one, because the two differ in the part that is easy to get wrong. Cold memory reads a
 * flat directory of notes and can assume a complete listing on every pass. The library walks a
 * tree the user pointed at, of unknown size, and therefore reads a *bounded part* of it per round.
 * That difference is exactly what [WorkspaceLibrarySource.sync]'s `keep` parameter exists to
 * respect, and threading it through a shared implementation would have hidden it.
 *
 * A round is: walk, filter, plan against the budget, read what fits, sync. Which files were read
 * is a property of the round, not of the directory, so "indexed" converges over rounds instead of
 * being all-or-nothing.
 */
class LibraryIndexCoordinator(
    private val scope: CoroutineScope,
    private val embeddings: EmbeddingService,
    private val source: WorkspaceLibrarySource,
    private val workspaceRepository: WorkspaceRepository,
    /**
     * Reads the text out of an image file. A parameter with a default rather than a constructor of
     * its own, so the wiring in `DataSourceModule` does not have to know about the recognizer.
     */
    private val imageText: ImageTextExtractor = ImageTextExtractor(),
    /**
     * What a vision model says an image shows, or null when the library is not allowed to ask one.
     *
     * A function rather than a dependency of its own, so that this class never has to know about
     * providers, prompts or caches: it decides *when* a picture is worth describing, and the
     * implementation decides whether and how to ask.
     */
    private val visionSummary: suspend (path: String, sizeBytes: Long, file: File) -> String? =
        { _, _, _ -> null },
) {

    /** What the settings screen shows, and what a log line is written from. */
    data class Status(
        val running: Boolean = false,
        val lastReport: Report? = null,
        val lastError: String? = null,
        /** Epoch millis of the last completed run, successful or not. */
        val lastRunAtMs: Long = 0L,
    )

    /**
     * One round, in the terms a person needs to understand why their library is not fully
     * searchable: how much of it was seen, how much of that was read, and what is left.
     */
    data class Report(
        val dirLabel: String,
        /** Files the walk found that the allow-list accepted. */
        val candidates: Int,
        /** Files read this round. */
        val read: Int,
        /** Files read this round that failed to open or parse. */
        val failed: Int,
        /** Accepted files this round had no budget for: the next round starts with them. */
        val deferred: Int,
        /** Files refused by a size cap. */
        val tooBig: Int,
        /** The walk itself hit its depth or entry limit. */
        val walkTruncated: Boolean,
        val indexedNow: Int,
        val unchanged: Int,
        /** Files in the index for this directory, this round included. */
        val indexedTotal: Int,
        val chunks: Int,
        val embeddedChunks: Int,
        val reason: String? = null,
    ) {
        /** True when there is more library than this round could read. */
        val hasMore: Boolean get() = deferred > 0
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val lastAttemptAtMs = mutableMapOf<String, Long>()

    /**
     * The workspace and directory of one assistant's library, or null when it has none configured.
     * Blank means the default directory; an explicit `/workspace` means the whole workspace, which
     * the rules allow and the round budget makes survivable.
     */
    private fun scopeOf(assistant: Assistant): Pair<String, String>? {
        if (!assistant.libraryEnabled) return null
        val workspaceId = assistant.workspaceId?.toString() ?: return null
        val raw = assistant.libraryDir.trim()
        val dir = if (raw.isEmpty()) {
            WorkspaceLibraryRules.DEFAULT_DIR
        } else {
            // normalizeDir rejects anything that tries to leave the workspace by returning null.
            ColdMemoryRules.normalizeDir(raw) ?: return null
        }
        return workspaceId to dir
    }

    /** The `/workspace/...` path a person sees, in the same form cold memory uses. */
    fun labelOf(dir: String): String = if (dir.isEmpty()) {
        ColdMemoryRules.WORKSPACE_PREFIX
    } else {
        "${ColdMemoryRules.WORKSPACE_PREFIX}/$dir"
    }

    /**
     * The label of this assistant's library, or null when it has none configured - which is the
     * question the tool surface asks before registering `library_search` at all.
     */
    fun dirLabelOf(assistant: Assistant): String? = scopeOf(assistant)?.let { labelOf(it.second) }

    /**
     * Fires a background sync unless one ran for this assistant within [minIntervalMs].
     * Returns false when it was skipped, which is not a failure - it is the debounce working.
     */
    fun requestSync(assistant: Assistant, minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS): Boolean {
        val (workspaceId, dir) = scopeOf(assistant) ?: return false
        val key = "${assistant.id}:$workspaceId:$dir"
        val now = System.currentTimeMillis()
        val previous = lastAttemptAtMs[key] ?: 0L
        if (now - previous < minIntervalMs) {
            Log.d(TAG, "skipping sync of '$dir': one ran ${(now - previous) / 1000}s ago")
            return false
        }
        lastAttemptAtMs[key] = now

        scope.launch {
            val report = runCatching { syncNow(assistant) }
                .onFailure { Log.d(TAG, "background sync failed", it) }
                .getOrNull()
            if (report == null) {
                // Nothing ran - most often because no model is installed yet. Do not let that
                // count as this assistant's attempt for the next five minutes: the user is
                // probably installing the model right now.
                lastAttemptAtMs.remove(key)
            }
        }
        return true
    }

    /**
     * Runs one round now and returns its report, or null when there is nothing to index. Failures
     * are recorded in [status] and returned as a null report rather than thrown: this runs from a
     * conversation opening, and no conversation should fail to open because an index could not be
     * built.
     */
    suspend fun syncNow(assistant: Assistant): Report? {
        val (workspaceId, dir) = scopeOf(assistant) ?: return null
        if (embeddings.ensureLoaded() == null) {
            _status.value = Status(
                running = false,
                lastError = "no embedding model is installed",
                lastRunAtMs = System.currentTimeMillis(),
            )
            return null
        }

        _status.value = _status.value.copy(running = true, lastError = null)
        return try {
            ensureDirectory(workspaceId, dir)
            val walk = walk(workspaceId, dir)
            val round = WorkspaceLibraryRules.planRound(
                candidates = walk.candidates,
                indexed = source.indexedPaths(workspaceId, dir),
            )

            val docs = mutableListOf<WorkspaceLibrarySource.Doc>()
            var failed = 0
            for (candidate in round.take) {
                val doc = readDoc(workspaceId, candidate)
                if (doc == null) {
                    failed++
                } else {
                    docs += doc
                }
            }

            val synced = source.sync(
                workspaceId = workspaceId,
                dir = dir,
                docs = docs,
                // Everything the walk saw stays: a file this round deferred, or failed to read, is
                // not a file that was deleted.
                keep = walk.candidates.map { it.path }.toSet(),
                nowMs = System.currentTimeMillis(),
            )

            val report = Report(
                dirLabel = labelOf(dir),
                candidates = walk.candidates.size,
                read = docs.size,
                failed = failed,
                deferred = round.deferred,
                tooBig = round.tooBig.size,
                walkTruncated = walk.truncated,
                indexedNow = synced.indexed,
                unchanged = synced.unchanged,
                indexedTotal = source.indexedFiles(workspaceId, dir),
                chunks = synced.chunks,
                embeddedChunks = synced.embeddedChunks,
                reason = synced.reason,
            )
            _status.value = Status(
                running = false,
                lastReport = report,
                lastRunAtMs = System.currentTimeMillis(),
            )
            Log.d(
                TAG,
                "synced '${report.dirLabel}': ${report.read} read, ${report.indexedNow} indexed, " +
                    "${report.embeddedChunks} chunk(s) embedded, ${report.deferred} deferred, " +
                    "${report.indexedTotal} in the index",
            )
            report
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = Status(
                running = false,
                lastError = e.message ?: e::class.java.simpleName,
                lastRunAtMs = System.currentTimeMillis(),
            )
            Log.d(TAG, "sync of '$dir' failed", e)
            null
        }
    }

    /**
     * Semantic search, in the shape the tool layer wants.
     *
     * A library that is configured but has never been indexed answers `available = true` with no
     * hits and `indexedFiles = 0`, which the envelope turns into "nothing is indexed yet" - a
     * different sentence from "semantic search is not set up", and the right one.
     */
    suspend fun search(assistant: Assistant, query: String, limit: Int): LibrarySearchOutcome {
        val (workspaceId, dir) = scopeOf(assistant) ?: return LibrarySearchOutcome(
            available = false,
            note = "this assistant has no file library configured",
        )
        return source.search(workspaceId, dir, query, limit)
    }

    private data class Walk(
        val candidates: List<WorkspaceLibraryRules.Candidate>,
        val truncated: Boolean,
    )

    /**
     * Lists the library recursively and keeps what the allow-list accepts.
     *
     * The walk's own limits (depth, entry count) come from the workspace layer and are reported
     * rather than worked around: a library too deep or too large to list is a fact the person
     * should see, not something to paper over by quietly indexing a prefix.
     */
    private suspend fun walk(workspaceId: String, dir: String): Walk {
        val root = labelOf(dir)
        val tree = try {
            workspaceRepository.readFolderTree(workspaceId, root)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The directory does not exist yet, or the workspace is gone. Either way: nothing to
            // index, and not an error worth failing the round over.
            Log.d(TAG, "cannot list '$root': ${e.message}")
            return Walk(emptyList(), truncated = false)
        }

        val candidates = tree.entries
            .filter { !it.isDirectory }
            .mapNotNull { entry ->
                // entry.path is relative to the walked root; a file inside an ignored directory is
                // skipped by segment, so `a/node_modules/b.md` is caught as reliably as the root.
                if (WorkspaceLibraryRules.isIgnoredPath(entry.path)) return@mapNotNull null
                val kind = WorkspaceLibraryRules.kindOf(entry.name) ?: return@mapNotNull null
                WorkspaceLibraryRules.Candidate(
                    path = "$root/${entry.path}",
                    name = entry.name,
                    kind = kind,
                    sizeBytes = entry.sizeBytes,
                )
            }
        return Walk(candidates, tree.truncated)
    }

    /**
     * Reads one file into the text that will be embedded.
     *
     * Documents go through the same parsers the chat uses for an uploaded attachment, so a PDF the
     * model can read in a message is a PDF the library can index. Images go through on-device text
     * recognition and are indexed as the words they contain - see [ImageTextExtractor]. The cap is
     * applied after extraction, because that is where the cost is.
     */
    private suspend fun readDoc(
        workspaceId: String,
        candidate: WorkspaceLibraryRules.Candidate,
    ): WorkspaceLibrarySource.Doc? {
        val relative = candidate.path.removePrefix("${ColdMemoryRules.WORKSPACE_PREFIX}/")
        return try {
            val text = when (candidate.kind) {
                LibraryFileKind.DOCUMENT -> {
                    val parser = WorkspaceLibraryRules.documentParserFor(candidate.name)
                        ?: return null
                    val file = workspaceRepository.resolveFile(
                        workspaceId,
                        WorkspaceStorageArea.FILES,
                        relative,
                    )
                    withContext(Dispatchers.IO) { parseDocument(file, parser) }
                }

                LibraryFileKind.IMAGE -> {
                    val file = workspaceRepository.resolveFile(
                        workspaceId,
                        WorkspaceStorageArea.FILES,
                        relative,
                    )
                    val recognised = withContext(Dispatchers.IO) { imageText.extract(file) }
                    // The second opinion, when the user turned it on: what the picture *is*, which
                    // is what a search for it usually has in mind and what recognition alone cannot
                    // produce. Both halves are indexed as one passage, and when neither produced
                    // anything the file's own name is - an image the index never records is one the
                    // next round reads first, and now that it can be described, uploads first.
                    val described = visionSummary(candidate.path, candidate.sizeBytes, file)
                    ImageSummaryRules.compose(recognised, described, candidate.name)
                }

                else -> workspaceRepository.readText(workspaceId, relative)
            }
            val capped = text.take(WorkspaceLibraryRules.charCapOf(candidate.kind))
            if (capped.isBlank()) return null
            WorkspaceLibrarySource.Doc(
                path = candidate.path,
                text = capped,
                chunkMode = WorkspaceLibraryRules.chunkModeOf(candidate.kind, candidate.name),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "cannot read '${candidate.path}', skipping it this round: ${e.message}")
            null
        }
    }

    private fun parseDocument(file: File, parser: LibraryDocumentParser): String = when (parser) {
        LibraryDocumentParser.PDF -> PdfParser.parserPdf(file)
        LibraryDocumentParser.DOCX -> DocxParser.parse(file)
        LibraryDocumentParser.PPTX -> PptxParser.parse(file)
        LibraryDocumentParser.EPUB -> EpubParser.parse(file)
    }

    /**
     * Creates the library directory on first use, so the person has somewhere to put files instead
     * of being told by a status line that the directory they picked does not exist.
     */
    private suspend fun ensureDirectory(workspaceId: String, dir: String) {
        if (dir.isEmpty()) return
        try {
            workspaceRepository.createDirectory(workspaceId, WorkspaceStorageArea.FILES, dir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "cannot create '$dir': ${e.message}")
        }
    }

    companion object {
        /**
         * Five minutes, matching cold memory: long enough that flicking between conversations is
         * free, short enough that a file saved now is searchable in the same sitting.
         */
        const val DEFAULT_MIN_INTERVAL_MS = 5 * 60 * 1000L
    }
}
