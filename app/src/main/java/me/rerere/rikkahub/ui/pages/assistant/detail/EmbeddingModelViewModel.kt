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

    data class Download(
        val fileName: String,
        val percent: Int,
        val bytesRead: Long,
        val totalBytes: Long?,
    )

    private val _download = MutableStateFlow<Download?>(null)
    val download: StateFlow<Download?> = _download.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _installed = MutableStateFlow(EmbeddingModelFiles.installedFiles(context))
    val installed: StateFlow<List<String>> = _installed.asStateFlow()

    /**
     * One curated model as the picker shows it: the file, the cell of the grid it occupies (null
     * for a model the grid does not place), and whether this phone has room for it.
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

    private var job: Job? = null

    /** Re-reads the directory. Called when the picker opens - the user may have just installed. */
    fun refresh() {
        _installed.value = EmbeddingModelFiles.installedFiles(context)
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
    fun download(entry: LlamaCppEmbeddingEntry, onInstalled: () -> Unit = {}) {
        if (job?.isActive == true) return
        _error.value = null
        _download.value = Download(entry.file, 0, 0L, entry.sizeBytes)
        job = viewModelScope.launch {
            val target = File(EmbeddingModelFiles.dir(context), entry.file)
            try {
                ModelInstall.download(httpClient, entry.resolveUrl(), target).collect { progress ->
                    when (progress) {
                        is ModelInstall.Progress.Started ->
                            _download.value = Download(entry.file, 0, 0L, progress.totalBytes)

                        is ModelInstall.Progress.Tick -> {
                            val total = progress.totalBytes
                            val percent =
                                if (total != null && total > 0) {
                                    ((progress.bytesRead * 100) / total).toInt()
                                } else {
                                    0
                                }
                            _download.value = Download(entry.file, percent, progress.bytesRead, total)
                        }

                        is ModelInstall.Progress.Done -> {
                            _download.value = null
                            refresh()
                            settingsStore.update { it.copy(embeddingModelFile = entry.file) }
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
}
