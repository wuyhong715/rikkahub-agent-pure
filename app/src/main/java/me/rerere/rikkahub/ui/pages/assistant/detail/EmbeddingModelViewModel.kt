package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.llamacpp.EmbeddingFocus
import me.rerere.llamacpp.EmbeddingTier
import me.rerere.llamacpp.LlamaCppEmbeddingCatalog
import me.rerere.llamacpp.LlamaCppEmbeddingEntry
import me.rerere.locallm.ModelInstall
import me.rerere.locallm.litert.LitertEmbeddingCatalog
import me.rerere.locallm.litert.LitertEmbeddingEntry
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.vector.EmbeddingDeviceFacts
import me.rerere.rikkahub.data.vector.EmbeddingDeviceProbe
import me.rerere.rikkahub.data.vector.EmbeddingModelFiles
import me.rerere.rikkahub.data.vector.EmbeddingTierRules
import okhttp3.OkHttpClient

/**
 * Installing an embedding model, without asking the user to know a URL.
 *
 * The supporting cast existed already - [ModelInstall] downloads with resume and magic-byte
 * validation, the local-model page has been driving it for a while - but nothing offered the
 * curated embedding models. A user who wanted semantic search had to find that page, know the
 * repository, and paste a `resolve/main` URL into it, which is not a thing to ask of somebody who
 * just wants search to work.
 */
class EmbeddingModelViewModel(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    /**
     * One in-flight download, as the card under the hero renders it.
     *
     * [percent] is nullable on purpose. The old card computed `bytes / total` and fell back to
     * `0` when the server sent no `Content-Length` (chunked responses, some CDNs, a 206 without
     * `Content-Range`), so a perfectly healthy transfer showed 0% from start to finish - the
     * "progress bar stuck" report that had nothing to do with the network. `null` here means
     * "unknown", which the card draws as an indeterminate bar plus the byte count it does know.
     *
     * [bytesPerSecond] and [retry] are what turn a silent stall into an explanation: speed shows
     * whether anything is still arriving, and the retry line says which host is being tried and
     * why the previous one went away.
     */
    data class Download(
        val fileName: String,
        val percent: Int?,
        val bytesRead: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long = 0L,
        val retry: Retry? = null,
    ) {
        data class Retry(
            val attempt: Int,
            val maxAttempts: Int,
            val host: String,
            val reason: String,
        )
    }

    private val _download = MutableStateFlow<Download?>(null)
    val download: StateFlow<Download?> = _download.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _installed = MutableStateFlow(EmbeddingModelFiles.installedFiles(context))
    val installed: StateFlow<List<String>> = _installed.asStateFlow()

    /**
     * Total size on disk of everything [installed] lists, in bytes. Recomputed by [refresh], so a
     * finished download or a deletion shows up without the screen having to count files itself.
     */
    private val _installedBytes = MutableStateFlow(measureInstalledBytes())
    val installedBytes: StateFlow<Long> = _installedBytes.asStateFlow()

    /**
     * One curated GGUF as the picker shows it: the file, the cell of the grid it occupies (null for
     * a model the grid does not place), and whether this phone has room for it.
     *
     * The picker needs all three at once - what to call it, which rung and lane it belongs to, and
     * whether the download button is an offer or a mistake - and computing them together is what
     * stops the row from disagreeing with the recommendation above it.
     */
    data class Offer(
        val entry: LlamaCppEmbeddingEntry,
        val tier: EmbeddingTier?,
        val focus: EmbeddingFocus?,
        val fits: Boolean,
    )

    /**
     * What this phone is, measured once.
     *
     * Deliberately not a flow: total RAM does not change, and free space changing mid-dialog is
     * not worth a filesystem read on every recomposition. A user who frees up space re-opens the
     * picker, and `refresh()` already re-reads the directory on the way in.
     */
    private val facts: EmbeddingDeviceFacts by lazy { EmbeddingDeviceProbe.facts(context) }

    /**
     * The model this phone should be pointed at, or null when none of the grid fits it.
     *
     * Null is a real answer: it means the phone has too little room for even the light rung, and
     * the picker shows the list without a recommendation rather than suggesting a download that
     * would not fit.
     */
    val recommended: LlamaCppEmbeddingEntry? by lazy {
        EmbeddingTierRules.recommendedSlot(facts)?.entry
    }

    /** Whether this phone is on a connection that bills per byte; see [EmbeddingDeviceProbe]. */
    val onMeteredNetwork: Boolean by lazy { EmbeddingDeviceProbe.isMetered(context) }

    /**
     * Curated models that are not on disk yet, in the order the picker should offer them - the
     * grid first, rung by rung, and then anything the grid does not place. Recomputed on every
     * read rather than cached: the only thing that changes it is a finished download, and
     * [installed] is what that updates.
     */
    val offers: List<Offer>
        get() = LlamaCppEmbeddingCatalog.PICKER_ORDER
            .filter { it.file !in _installed.value }
            .map { entry ->
                val slot = LlamaCppEmbeddingCatalog.SLOTS.firstOrNull { it.entry.file == entry.file }
                Offer(
                    entry = entry,
                    tier = slot?.tier,
                    focus = slot?.focus,
                    fits = EmbeddingTierRules.fits(entry, facts),
                )
            }

    /**
     * The curated `.litertlm` files that are not on disk yet, with whether this phone can take
     * them.
     *
     * A second list rather than more [Offer]s, because the two runtimes' entries are different
     * types on purpose - a GGUF carries a pooling rule, a `.litertlm` one carries an input signature
     * and says whether the model can see - and each downloads into its own directory. The screen
     * renders them in one list; the type is what keeps a `.gguf` out of the litert directory.
     */
    val litertOffers: List<LitertOffer>
        get() = LitertEmbeddingCatalog.ENTRIES
            .filter { it.file !in _installed.value }
            .map { entry -> LitertOffer(entry, EmbeddingTierRules.fits(entry.sizeBytes, facts)) }

    /** One curated `.litertlm` as the picker shows it: the file, and whether it fits this phone. */
    data class LitertOffer(val entry: LitertEmbeddingEntry, val fits: Boolean)

    private var job: Job? = null

    /**
     * What the last attempt asked for, so the error card's Retry can re-run it instead of making
     * the user go back and find the row they tapped.
     */
    private data class LastRequest(
        val fileName: String,
        val sizeBytes: Long,
        val url: String,
        val target: File,
        val onInstalled: () -> Unit,
    )

    private var lastRequest: LastRequest? = null

    // Speed sampling: bytes and clock at the previous tick, smoothed so a single slow read does
    // not make the figure jump between "12 MB/s" and "300 KB/s".
    private var sampleAtNanos = 0L
    private var sampleBytes = 0L
    private var smoothedBytesPerSecond = 0L

    /** Re-reads the directory. Called when the picker opens - the user may have just installed. */
    fun refresh() {
        _installed.value = EmbeddingModelFiles.installedFiles(context)
        _installedBytes.value = measureInstalledBytes()
    }

    private fun measureInstalledBytes(): Long =
        EmbeddingModelFiles.dirs(context)
            .flatMap { dir -> dir.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile }
            .sumOf { it.length() }

    /**
     * Removes an installed model from disk.
     *
     * Deleting the file the setting points at would leave a configured name that resolves to
     * nothing, so that case resets the choice to automatic: the lookup then picks the best of what
     * is left, which is what "automatic" means everywhere else in the app.
     */
    fun delete(fileName: String) {
        EmbeddingModelFiles.fileFor(context, fileName)?.delete()
        if (settingsStore.settingsFlow.value.embeddingModelFile == fileName) {
            viewModelScope.launch {
                settingsStore.update { it.copy(embeddingModelFile = "") }
            }
        }
        refresh()
    }

    fun cancel() {
        job?.cancel()
        job = null
        _download.value = null
    }

    /**
     * Downloads [entry] into the directory the embedding lookup reads, then selects it.
     *
     * Selecting it afterwards is the point of doing this from here: the setting is empty
     * ("automatic") on a fresh install, and a user who has just waited for a file to arrive
     * should not have to go and pick it out of a list.
     *
     * [onInstalled] runs after the file is on disk and selected, for a caller that wants to start
     * indexing with it.
     *
     * Deliberately does *not* register the file as a chat model in the local provider the way the
     * local-model page does on completion. These files cannot hold a conversation, and offering
     * one as if it could is a trap; the embedding lookup finds them by listing the directory, so
     * nothing else needs to know they exist.
     */
    fun download(entry: LlamaCppEmbeddingEntry, onInstalled: () -> Unit = {}) =
        downloadInto(
            fileName = entry.file,
            sizeBytes = entry.sizeBytes,
            url = entry.resolveUrl(),
            target = File(EmbeddingModelFiles.dir(context), entry.file),
            onInstalled = onInstalled,
        )

    /**
     * The same thing for a `.litertlm`, which goes into its own directory.
     *
     * A separate entry point rather than a common one over a union type, because the two entries
     * share exactly three fields and everything else about them - the pooling rule, the input
     * signature, whether the model can see - is meaningful for one runtime only.
     */
    fun download(entry: LitertEmbeddingEntry, onInstalled: () -> Unit = {}) =
        downloadInto(
            fileName = entry.file,
            sizeBytes = entry.sizeBytes,
            url = entry.resolveUrl(),
            target = File(EmbeddingModelFiles.litertDir(context), entry.file),
            onInstalled = onInstalled,
        )

    private fun downloadInto(
        fileName: String,
        sizeBytes: Long,
        url: String,
        target: File,
        onInstalled: () -> Unit,
    ) {
        if (job?.isActive == true) return
        _error.value = null
        lastRequest = LastRequest(fileName, sizeBytes, url, target, onInstalled)
        sampleAtNanos = 0L
        sampleBytes = 0L
        smoothedBytesPerSecond = 0L
        // A known length from the catalogue even before the server says anything: a curated entry
        // always declares its size, so the first frames show a real percentage rather than 0%.
        val catalogueSize = sizeBytes.takeIf { it > 0 }
        _download.value = Download(
            fileName = fileName,
            // 0% only when there is a length to be 0% of; otherwise the card starts out
            // indeterminate instead of claiming a percentage it cannot know.
            percent = if (catalogueSize != null) 0 else null,
            bytesRead = 0L,
            totalBytes = catalogueSize,
        )
        job = viewModelScope.launch {
            try {
                ModelInstall.download(
                    client = httpClient,
                    url = url,
                    target = target,
                    // The mirror is used on failure only; see ModelDownloadPolicy.
                    mirrorEnabled = settingsStore.settingsFlow.value.networkSetting.huggingFaceMirror,
                ).collect { progress ->
                    when (progress) {
                        is ModelInstall.Progress.Started -> {
                            val total = progress.totalBytes ?: sizeBytes.takeIf { it > 0 }
                            _download.value = Download(
                                fileName = fileName,
                                percent = if (total != null) 0 else null,
                                bytesRead = 0L,
                                totalBytes = total,
                            )
                        }

                        is ModelInstall.Progress.Tick -> {
                            // A server that declares no length is common enough (chunked, some
                            // mirrors) that the catalogue size is the better answer when we have
                            // one; only when neither exists does the bar go indeterminate.
                            val total = progress.totalBytes ?: sizeBytes.takeIf { it > 0 }
                            val percent = total
                                ?.takeIf { it > 0 }
                                ?.let { ((progress.bytesRead * 100) / it).toInt().coerceIn(0, 100) }
                            _download.value = Download(
                                fileName = fileName,
                                percent = percent,
                                bytesRead = progress.bytesRead,
                                totalBytes = total,
                                bytesPerSecond = sampleSpeed(progress.bytesRead),
                                retry = _download.value?.retry,
                            )
                        }

                        is ModelInstall.Progress.Retrying -> {
                            // Keep the bytes already on disk and the percentage they represent:
                            // the next attempt resumes from there, so the bar must not snap back
                            // to zero while the mirror is being dialled.
                            val current = _download.value
                            sampleAtNanos = 0L
                            _download.value = Download(
                                fileName = fileName,
                                percent = current?.percent,
                                bytesRead = current?.bytesRead ?: 0L,
                                totalBytes = current?.totalBytes ?: sizeBytes.takeIf { it > 0 },
                                bytesPerSecond = 0L,
                                retry = Download.Retry(
                                    attempt = progress.attempt,
                                    maxAttempts = progress.maxAttempts,
                                    host = progress.host,
                                    reason = progress.reason,
                                ),
                            )
                        }

                        is ModelInstall.Progress.Done -> {
                            _download.value = null
                            refresh()
                            settingsStore.update { it.copy(embeddingModelFile = fileName) }
                            // The caller builds the index with it: a model that has just been
                            // waited for should not need a conversation to be opened before
                            // anything happens.
                            onInstalled()
                        }

                        is ModelInstall.Progress.Failed -> {
                            _download.value = null
                            _error.value = progress.cause.message ?: progress.cause::class.java.simpleName
                        }
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                // Cancelling is a normal end to a download the user changed their mind about.
                _download.value = null
                throw cancel
            } catch (t: Throwable) {
                // The flow catches IOException by itself; anything deeper (an interceptor, a
                // socket close racing cancellation) would otherwise reach the view model's scope.
                _download.value = null
                _error.value = t.message ?: t::class.java.simpleName
            }
        }
    }

    /**
     * Re-runs the last attempt - the error card's button.
     *
     * A download that ran out of automatic retries kept its `.partial`, so this resumes rather
     * than starting the multi-hundred-megabyte file again.
     */
    fun retryLast() {
        val last = lastRequest ?: return
        downloadInto(
            fileName = last.fileName,
            sizeBytes = last.sizeBytes,
            url = last.url,
            target = last.target,
            onInstalled = last.onInstalled,
        )
    }

    /**
     * Bytes per second since the previous sample, exponentially smoothed.
     *
     * Deliberately computed from ticks rather than from a timer: a bar that shows "0 B/s" while
     * bytes are arriving is the same lie as a bar that shows none.
     */
    private fun sampleSpeed(bytesRead: Long): Long {
        val now = System.nanoTime()
        if (sampleAtNanos == 0L || bytesRead < sampleBytes) {
            sampleAtNanos = now
            sampleBytes = bytesRead
            return smoothedBytesPerSecond
        }
        val elapsed = now - sampleAtNanos
        if (elapsed < 200_000_000L) return smoothedBytesPerSecond
        val instant = ((bytesRead - sampleBytes) * 1_000_000_000L) / elapsed
        smoothedBytesPerSecond =
            if (smoothedBytesPerSecond == 0L) instant
            else (smoothedBytesPerSecond * 6 + instant * 4) / 10
        sampleAtNanos = now
        sampleBytes = bytesRead
        return smoothedBytesPerSecond
    }
}
