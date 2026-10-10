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
import me.rerere.llamacpp.LlamaCppEmbeddingCatalog
import me.rerere.llamacpp.LlamaCppEmbeddingEntry
import me.rerere.locallm.ModelInstall
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.vector.EmbeddingModelFiles
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
     * Curated models that are not on disk yet. Recomputed on every read rather than cached: the
     * only thing that changes it is a finished download, and [installed] is what that updates.
     */
    val downloadable: List<LlamaCppEmbeddingEntry>
        get() = LlamaCppEmbeddingCatalog.ENTRIES.filter { it.file !in _installed.value }

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
