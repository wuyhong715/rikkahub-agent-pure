package me.rerere.rikkahub.data.vector

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.tools.ColdMemoryRules
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageArea

private const val TAG = "MemoryIndex"

/**
 * Keeps the knowledge-base index up to date, in the background.
 *
 * Deliberately *not* triggered by every write: embedding is a model call per chunk, and a feature
 * that spends it on every keystroke of a note is a feature users turn off. Two triggers instead,
 * matching what the settings screen offers:
 *
 *  - opening a conversation, which is when the model is about to need the index - debounced, so
 *    switching between conversations does not re-scan a directory the last minute already
 *    covered;
 *  - an explicit rebuild from settings, which ignores the debounce.
 *
 * Both paths are incremental: [MemoryVectorSource.sync] compares fingerprints first and only
 * chunks that changed cost a model call, so the common case (nothing changed) is a directory
 * listing and a hash per document.
 */
class MemoryIndexCoordinator(
    private val scope: CoroutineScope,
    private val embeddings: EmbeddingService,
    private val source: MemoryVectorSource,
    private val workspaceRepository: WorkspaceRepository,
    private val settingsStore: SettingsStore,
) {

    /** What the settings screen shows, and what a log line is written from. */
    data class Status(
        val running: Boolean = false,
        val lastReport: MemoryVectorSource.SyncReport? = null,
        val lastError: String? = null,
        /** Epoch millis of the last completed run, successful or not. */
        val lastRunAtMs: Long = 0L,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val lastAttemptAtMs = mutableMapOf<String, Long>()

    /**
     * The scope key for one assistant's knowledge base, or null when it has none configured.
     * Shared with the tool so that a search and a sync always agree on what "this directory"
     * means.
     */
    private fun scopeOf(assistant: Assistant): Pair<String, String>? {
        if (!assistant.coldMemoryEnabled) return null
        val workspaceId = assistant.workspaceId?.toString() ?: return null
        val dir = ColdMemoryRules.normalizeDir(assistant.coldMemoryDir) ?: return null
        return workspaceId to dir
    }

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
            // Logged because the debounce is otherwise invisible: "the index is stale and nothing
            // is happening" is indistinguishable from "the trigger never fired", and that cost a
            // diagnostic round on the first device test.
            Log.d(TAG, "skipping sync of '$dir': one ran ${(now - previous) / 1000}s ago")
            return false
        }
        lastAttemptAtMs[key] = now

        scope.launch {
            val report = runCatching { syncNow(assistant) }
                .onFailure { Log.d(TAG, "background sync failed", it) }
                .getOrNull()
            if (report == null) {
                // Nothing was indexed - most often because no model is installed yet. Do not let
                // that count as this assistant's one attempt for the next five minutes: the user
                // is probably installing the model right now, and the next conversation they open
                // should try again rather than wait out a debounce for work that never ran.
                lastAttemptAtMs.remove(key)
            }
        }
        return true
    }

    /**
     * Runs a sync now and returns its report, or null when there is nothing to index. Failures
     * are recorded in [status] and returned as a null report rather than thrown: this runs from
     * a conversation opening, and no conversation should fail to open because an index could not
     * be built.
     */
    suspend fun syncNow(assistant: Assistant): MemoryVectorSource.SyncReport? {
        val (workspaceId, dir) = scopeOf(assistant) ?: return null
        val models = embeddings.ensureLoaded()
        if (models == null) {
            _status.value = Status(
                running = false,
                lastError = "no embedding model is installed",
                lastRunAtMs = System.currentTimeMillis(),
            )
            return null
        }

        _status.value = _status.value.copy(running = true, lastError = null)
        return try {
            val docs = readDocuments(workspaceId, dir)
            val report = source.sync(dir, docs, System.currentTimeMillis())
            _status.value = Status(
                running = false,
                lastReport = report,
                lastRunAtMs = System.currentTimeMillis(),
            )
            Log.d(TAG, "synced '$dir': ${report.indexed} indexed, ${report.unchanged} unchanged, " +
                "${report.embeddedChunks} chunk(s) embedded")
            report
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
     * Reads every Markdown document in [dir].
     *
     * A file that cannot be read is dropped from the listing, and that is the one place this
     * design can lose index rows: the sync treats a missing document as deleted. It is the
     * lesser evil against the alternative - refusing to sync at all because one file is
     * unreadable - but it is why the read failure is logged loudly rather than swallowed.
     */
    private suspend fun readDocuments(workspaceId: String, dir: String): List<MemoryVectorSource.Doc> {
        val entries = try {
            workspaceRepository.listFiles(workspaceId, WorkspaceStorageArea.FILES, dir)
                .filter { !it.isDirectory && ColdMemoryRules.isMarkdown(it.name) }
        } catch (e: Throwable) {
            // The directory may not exist yet; cold memory creates it on first write.
            Log.d(TAG, "cannot list '$dir'", e)
            return emptyList()
        }

        val docs = mutableListOf<MemoryVectorSource.Doc>()
        for (entry in entries) {
            val path = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
            try {
                docs += MemoryVectorSource.Doc(entry.name, workspaceRepository.readText(workspaceId, path))
            } catch (e: Throwable) {
                Log.d(TAG, "cannot read '$path', skipping it this round", e)
            }
        }
        return docs
    }

    /**
     * Semantic search, in the shape the tool layer wants.
     *
     * The mapping lives here rather than in the tool so that the tool keeps holding nothing but
     * the model-facing contract, and so a hit's `docKey` is translated to a file name in exactly
     * one place - the same name `memory_read` accepts.
     */
    suspend fun search(dir: String, query: String, limit: Int): ColdMemorySearchOutcome {
        val outcome = source.search(dir, query, limit)
        return ColdMemorySearchOutcome(
            available = outcome.available,
            hits = outcome.hits.map {
                ColdMemorySearchHit(
                    file = it.docKey,
                    chunkIndex = it.chunkIndex,
                    score = it.score,
                    text = it.text,
                )
            },
            indexedDocuments = outcome.indexedDocuments,
            stale = outcome.stale,
        )
    }

    companion object {
        /**
         * Five minutes. Long enough that flicking between conversations is free, short enough
         * that a note written now is searchable in the same sitting.
         */
        const val DEFAULT_MIN_INTERVAL_MS = 5 * 60 * 1000L
    }
}
