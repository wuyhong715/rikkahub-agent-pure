package me.rerere.locallm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Bytes read from the head of a file to identify its format (offset 4..7 matters). */
private const val MAGIC_SNIFF_BYTES = 16

/** How often a download reports bytes while it is moving; see [ModelInstall.download]. */
private const val TICK_INTERVAL_MILLIS = 200L

private const val TICK_INTERVAL_NANOS = TICK_INTERVAL_MILLIS * 1_000_000L

private const val TAG = "ModelInstall"

/**
 * Downloads a local-LLM model file from a URL into app-private storage.
 *
 * Resume-aware: a leftover `.partial` from a prior interrupted attempt triggers a
 * `Range: bytes=<existing-size>-` request, the server's 206 Partial Content response is
 * appended in place, and progress emits start at the resumed offset. Server-side fall-
 * backs: 200 OK → silently overwrite from 0; 416 → delete + signal restart-needed.
 *
 * Atomic rename to the final filename only on success, after a magic-byte integrity
 * check. Cancel-safe: a cancelled coroutine leaves no partial file (it is deleted on
 * failure paths and never renamed on cancellation before [Progress.Done]).
 *
 * Files land under `${context.filesDir}/local-models/litert/<basename>`.
 */
object ModelInstall {

    private const val LOCAL_MODELS_DIRNAME = "local-models"

    /**
     * Progress events emitted by [download]. Terminal events are [Done] and [Failed].
     */
    sealed class Progress {
        data class Started(val totalBytes: Long?) : Progress()
        data class Tick(val bytesRead: Long, val totalBytes: Long?) : Progress()

        /**
         * An attempt failed and another is about to start against [host] - possibly the mirror
         * rather than the host the user's URL named.
         *
         * Its own event rather than a silent restart, because the two reports this fixes were
         * both "nothing happened": the bar froze because nothing was said while the transfer was
         * dead. [reason] is the previous failure in one line.
         */
        data class Retrying(
            val attempt: Int,
            val maxAttempts: Int,
            val host: String,
            val reason: String,
        ) : Progress()

        data class Done(val file: File) : Progress()
        data class Failed(val cause: Throwable) : Progress()
    }

    /**
     * Normalise a HuggingFace URL so that both the viewer (blob) form and the direct
     * download (resolve) form work.
     *
     * HuggingFace "view file" URLs look like:
     *   https://huggingface.co/<user>/<repo>/blob/main/<file>
     * The actual download URL is:
     *   https://huggingface.co/<user>/<repo>/resolve/main/<file>
     *
     * Pasting the viewer URL into the manual-install field would previously succeed
     * HTTP-wise (200 OK) but return an HTML page, not the model binary. This transform
     * converts blob → resolve for any huggingface.co path. Non-HF URLs are returned
     * unchanged.
     */
    fun normalizeHuggingFaceUrl(url: String): String {
        // Both hosts carry the same path shape, and a blob URL pasted from the mirror is just as
        // wrong as one pasted from the hub.
        if (!url.contains("huggingface.co") && !url.contains("hf-mirror.com")) return url
        // Single regex handles all branches (main, dev, v1, etc.); the earlier literal
        // replace for /blob/main/ was redundant since the regex already covers it.
        return url.replace(Regex("/blob/([^/]+)/")) { m -> "/resolve/${m.groupValues[1]}/" }
    }

    fun isValidDownloadUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return runCatching {
            val parsed = java.net.URI(url)
            parsed.scheme == "https" && parsed.host?.isNotBlank() == true
        }.getOrDefault(false)
    }

    fun runtimeForExtension(extension: String): LocalRuntime? = when (extension.lowercase()) {
        "litertlm" -> LocalRuntime.LiteRT
        "gguf" -> LocalRuntime.LlamaCpp
        else -> null
    }

    fun extractFileNameFromUrl(url: String): String {
        val withoutQuery = url.substringBefore('?')
        return withoutQuery.substringAfterLast('/')
    }

    fun targetFile(baseDir: File, runtime: LocalRuntime, fileName: String): File =
        File(runtimeDir(baseDir, runtime), fileName)

    /**
     * The directory one runtime's files live in: `local-models/<runtime>/`.
     *
     * The single place that knows this. Every writer used to spell the subdirectory out itself,
     * and the embedding lookup did not - it listed `local-models/` itself, which contains only
     * the runtime directories and therefore never a `.gguf`. A reader and a writer that compute
     * the same path separately will eventually disagree; this is the one they now share, and
     * [me.rerere.rikkahub.data.vector.EmbeddingModelFiles] is the reader's side of it.
     */
    fun runtimeDir(baseDir: File, runtime: LocalRuntime): File {
        val sub = when (runtime) {
            LocalRuntime.LiteRT -> "litert"
            LocalRuntime.LlamaCpp -> "llamacpp"
        }
        return File(baseDir, sub).apply { mkdirs() }
    }

    fun localModelsDir(context: Context): File =
        File(context.filesDir, LOCAL_MODELS_DIRNAME).apply { mkdirs() }

    /**
     * Returns true if the first [count] bytes of [buf] look like an HTML document.
     * Used to abort downloads that succeeded HTTP-wise but returned an error page.
     */
    fun looksLikeHtml(buf: ByteArray, count: Int): Boolean {
        val sample = String(buf, 0, count.coerceAtMost(256), Charsets.UTF_8)
            .trimStart()
            .lowercase()
        return sample.startsWith("<!doctype") ||
               sample.startsWith("<html") ||
               sample.startsWith("<head") ||
               sample.startsWith("<body") ||
               sample.startsWith("<?xml")
    }

    /**
     * Returns true if [firstBytes] looks like a valid file of the type implied by
     * [extension]. Used as a post-download integrity check to refuse files that
     * downloaded successfully but whose contents are wrong (sparse-fill, server
     * misroute, partial corruption, etc.).
     */
    fun isValidMagicForExtension(extension: String, firstBytes: ByteArray): Boolean {
        if (firstBytes.size < 4) return false
        return when (extension.lowercase()) {
            "litertlm", "tflite", "task" -> {
                // LiteRT-LM files start with ASCII "LITERTLM"
                // (0x4c 0x49 0x54 0x45 0x52 0x54 0x4c 0x4d).
                // .tflite / .task files start with TFL3 (0x54 0x46 0x4c 0x33) at offset 4 in the
                // FlatBuffer header (offset 0..3 is the size prefix). The simplest check: first
                // 8 bytes are "LITERTLM" OR bytes 4..7 are "TFL3". Reject all-zero, all-FF,
                // or HTML-like first bytes.
                val isLitertlm = firstBytes.size >= 8 &&
                    firstBytes[0] == 0x4c.toByte() && firstBytes[1] == 0x49.toByte() &&
                    firstBytes[2] == 0x54.toByte() && firstBytes[3] == 0x45.toByte() &&
                    firstBytes[4] == 0x52.toByte() && firstBytes[5] == 0x54.toByte() &&
                    firstBytes[6] == 0x4c.toByte() && firstBytes[7] == 0x4d.toByte()
                val isTflite = firstBytes.size >= 8 &&
                    firstBytes[4] == 0x54.toByte() && firstBytes[5] == 0x46.toByte() &&
                    firstBytes[6] == 0x4c.toByte() && firstBytes[7] == 0x33.toByte()
                isLitertlm || isTflite
            }
            "gguf" -> {
                // GGUF files start with the ASCII magic "GGUF"
                // (0x47 0x47 0x55 0x46). A truncated download or an HTML error page
                // served in its place falls through to this arm without it and was
                // previously accepted as a model.
                firstBytes[0] == 0x47.toByte() && firstBytes[1] == 0x47.toByte() &&
                    firstBytes[2] == 0x55.toByte() && firstBytes[3] == 0x46.toByte()
            }
            else -> {
                // Unknown extension — accept by default but reject obvious zeros / HTML.
                !looksLikeHtml(firstBytes, firstBytes.size) && firstBytes.any { it != 0x00.toByte() }
            }
        }
    }

    /**
     * Download [url] into [target], and keep asking when the connection lets go.
     *
     * One request per download was enough on a network where huggingface.co answers, and it is
     * the reason for both reports that are not: a reset 400 MB into a 600 MB model threw away
     * the whole transfer, and a body that stopped arriving without closing looked exactly like a
     * slow one for the ten minutes the shared client's readTimeout allows - a frozen bar for ten
     * minutes, then an error.
     *
     * So an attempt is no longer the whole download:
     *
     *  * [ModelDownloadPolicy.candidates] gives the URLs worth trying, in order - the one asked
     *    for, then the `hf-mirror.com` equivalent when it is a HuggingFace hub URL and the
     *    mirror is enabled.
     *  * Each attempt resumes the `.partial` file the previous one left, so a reset costs the
     *    bytes that are missing rather than the bytes already on disk.
     *  * The `readTimeout` of a client derived from the caller's turns "silent" into "failed" in
     *    under a minute ([ModelDownloadPolicy.STALL_TIMEOUT_SECONDS]) instead of ten, so the bar
     *    starts moving again instead of hanging.
     *  * Failures another request cannot fix - a 404, an HTML page where a binary should be - end
     *    the flow immediately with [Progress.Failed]; the ones it can ([IOException], 5xx, 429, a
     *    short read) emit [Progress.Retrying] and try again.
     *
     * The atomic rename to [target] still happens only on success, after the magic-byte check, so
     * an interrupted download can never be mistaken for an installed model.
     *
     * HuggingFace blob URLs are automatically normalised to resolve URLs before the request is
     * issued. Callers may also pre-normalise with [normalizeHuggingFaceUrl].
     *
     * [mirrorEnabled] is the user's setting rather than a constant here: the mirror is a
     * third-party host, so falling back to it is a choice the caller makes on the user's behalf.
     * [maxAttempts] and [retryDelayMillis] are parameters so tests can pin the retry ladder
     * without waiting through it.
     */
    fun download(
        client: OkHttpClient,
        url: String,
        target: File,
        mirrorEnabled: Boolean = true,
        maxAttempts: Int = ModelDownloadPolicy.MAX_ATTEMPTS,
        retryDelayMillis: (Int) -> Long = ModelDownloadPolicy::backoffMillis,
    ): Flow<Progress> = flow {
        target.parentFile?.mkdirs()
        val partial = File(target.absolutePath + ".partial")
        // A copy, not the caller's client: the shared one keeps a ten-minute read timeout because
        // a long model prefill is legitimate, and that is the wrong budget for a download whose
        // peer has silently stopped sending. Connection pool and dispatcher are shared, so this
        // costs nothing beyond the timeout.
        val downloadClient = client.newBuilder()
            .readTimeout(ModelDownloadPolicy.STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        val candidates = ModelDownloadPolicy.candidates(normalizeHuggingFaceUrl(url), mirrorEnabled)
        var lastCause: Throwable? = null
        for (attempt in 1..maxAttempts) {
            val candidate = candidates[(attempt - 1) % candidates.size]
            if (attempt > 1) {
                // Announce before the pause: the user sees why the bar stopped instead of
                // watching a number that does not move.
                emit(
                    Progress.Retrying(
                        attempt = attempt,
                        maxAttempts = maxAttempts,
                        host = candidate.host,
                        reason = describeFailure(lastCause),
                    )
                )
                val wait = retryDelayMillis(attempt)
                if (wait > 0) delay(wait)
            }
            when (val outcome = attemptDownload(downloadClient, candidate, partial, target)) {
                is AttemptOutcome.Success -> {
                    emit(Progress.Done(outcome.file))
                    return@flow
                }
                is AttemptOutcome.Fatal -> {
                    emit(Progress.Failed(outcome.cause))
                    return@flow
                }
                is AttemptOutcome.Retryable -> lastCause = outcome.cause
            }
        }
        emit(Progress.Failed(exhaustedFailure(maxAttempts, lastCause)))
    }.flowOn(Dispatchers.IO)

    /** What one request did: it delivered the file, it could be asked again, or it is hopeless. */
    private sealed interface AttemptOutcome {
        data class Success(val file: File) : AttemptOutcome

        /** Another request (or another host) could plausibly do better; the partial is kept. */
        data class Retryable(val cause: Throwable) : AttemptOutcome

        /** Asking again would get the same answer: wrong URL, wrong content, unwritable target. */
        data class Fatal(val cause: Throwable) : AttemptOutcome
    }

    /**
     * One request against [candidate], resuming [partial] when it has bytes in it.
     *
     * Emits [Progress.Started] / [Progress.Tick] on the way and leaves the bytes on disk for the
     * next attempt when the outcome is [AttemptOutcome.Retryable].
     *
     * Bytes are emitted at most every [TICK_INTERVAL_MILLIS] rather than once per 64 KB read: a
     * 600 MB file is ~10 000 emits, and pushing 10 000 state updates through Compose for a
     * percentage that only changes a hundred times is work nobody sees. The final figure is
     * always emitted, so nothing depends on where the clock landed.
     */
    private suspend fun FlowCollector<Progress>.attemptDownload(
        client: OkHttpClient,
        candidate: ModelDownloadPolicy.Candidate,
        partial: File,
        target: File,
    ): AttemptOutcome {
        val resumeFrom = if (partial.exists() && partial.length() > 0) partial.length() else 0L
        val requestBuilder = Request.Builder().url(candidate.url)
        if (resumeFrom > 0) {
            requestBuilder.header("Range", "bytes=$resumeFrom-")
        }

        val response = try {
            client.newCall(requestBuilder.build()).execute()
        } catch (e: java.io.IOException) {
            // No response at all: DNS, connect, TLS, a proxy that dropped us. Nothing was
            // written, so the next attempt simply starts again.
            Log.w(TAG, "Download attempt failed to connect: ${e::class.simpleName}: ${e.message}")
            return AttemptOutcome.Retryable(e)
        }

        response.use { response ->
            // 416 Range Not Satisfiable - the partial no longer matches the server's file (it
            // grew, shrank, or the tail is corrupt). Drop it and start from zero; still worth a
            // retry, because the fresh request is a different download.
            if (response.code == 416 && resumeFrom > 0) {
                Log.w(TAG, "Server returned 416 (Range Not Satisfiable) — partial=$resumeFrom; restarting from 0")
                runCatching { partial.delete() }
                return AttemptOutcome.Retryable(
                    java.io.IOException("HTTP 416: the server would not resume at $resumeFrom bytes")
                )
            }
            if (!response.isSuccessful) {
                Log.w(TAG, "Download failed: HTTP ${response.code} for ${candidate.url}")
                val cause = IllegalStateException("HTTP ${response.code}")
                return if (ModelDownloadPolicy.isTransientHttpCode(response.code)) {
                    AttemptOutcome.Retryable(cause)
                } else {
                    AttemptOutcome.Fatal(cause)
                }
            }

            // Reject HTML responses immediately — a 200 OK from HuggingFace viewer pages,
            // Cloudflare error pages, a captive portal, etc. would otherwise land silently as an
            // HTML file. Worth another attempt (a mirror may answer properly), never a success.
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")) {
                Log.w(TAG, "Download rejected: HTML content-type '$contentType' for ${candidate.url}")
                runCatching { partial.delete() }
                return AttemptOutcome.Retryable(
                    IllegalStateException("HTTP ${response.code} returned an HTML page, not a model file")
                )
            }

            // 206 Partial Content means the server honoured our Range header → append to
            // the existing partial. 200 OK means the server ignored the Range header (or
            // we didn't send one) → overwrite the partial from byte 0. Total file size
            // comes from Content-Range when resuming (`bytes 1024-4095/4096`); from
            // Content-Length when fresh - and stays null when the server declares neither,
            // which the UI shows as an indeterminate bar rather than a 0% one.
            val isResume = response.code == 206 && resumeFrom > 0
            val total: Long? = if (isResume) {
                response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            } else {
                response.header("Content-Length")?.toLongOrNull()
            }
            emit(Progress.Started(total))

            val effectiveResumeFrom = if (isResume) resumeFrom else 0L
            if (effectiveResumeFrom > 0) {
                // Surface the current bytes-on-disk so the UI's first tick shows the right
                // starting percentage instead of jumping from 0% on the next data chunk.
                emit(Progress.Tick(effectiveResumeFrom, total))
            } else if (resumeFrom > 0) {
                // We sent Range but the server returned 200 — clear the partial so we don't
                // double-write its old contents.
                Log.w(TAG, "Server returned 200 (Range not honoured); restarting partial from 0")
                runCatching { partial.delete() }
            }

            val body = response.body

            // Skip the HTML magic-byte sniff when resuming — those bytes are already on disk from
            // the prior attempt, and the body now starts mid-file.
            var sniffed = isResume
            var bailed = false
            // FileOutputStream(file, append=true) when resuming so we extend rather than truncate.
            val out = if (isResume) {
                java.io.FileOutputStream(partial, /* append = */ true)
            } else {
                partial.outputStream()
            }

            // The read loop wraps everything that can go wrong mid-stream: SocketException
            // ("Software caused connection abort", "Connection reset"), SSL errors, and the
            // readTimeout this client sets for a silent peer. All of them leave the bytes already
            // written intact and resumable.
            var totalRead = effectiveResumeFrom
            var lastEmittedBytes = effectiveResumeFrom
            var lastEmitNanos = 0L
            var firstTick = true
            val ioFailure: Throwable? = try {
                out.use { sink ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            if (!sniffed) {
                                sniffed = true
                                if (looksLikeHtml(buf, n)) {
                                    bailed = true
                                    break
                                }
                            }
                            sink.write(buf, 0, n)
                            totalRead += n
                            val now = System.nanoTime()
                            if (firstTick || now - lastEmitNanos >= TICK_INTERVAL_NANOS) {
                                firstTick = false
                                lastEmitNanos = now
                                lastEmittedBytes = totalRead
                                emit(Progress.Tick(totalRead, total))
                            }
                        }
                    }
                }
                null
            } catch (e: java.io.IOException) {
                e
            }

            // Always land on the true byte count, throttled or not.
            if (totalRead != lastEmittedBytes) {
                emit(Progress.Tick(totalRead, total))
            }

            if (ioFailure != null) {
                Log.w(TAG, "Download read interrupted: ${ioFailure::class.simpleName}: ${ioFailure.message}")
                return AttemptOutcome.Retryable(ioFailure)
            }

            if (bailed) {
                runCatching { partial.delete() }
                Log.w(TAG, "Download aborted: HTML magic bytes detected in response body for ${candidate.url}")
                return AttemptOutcome.Retryable(
                    IllegalStateException("The server sent an HTML page instead of a model file")
                )
            }

            // A clean EOF (n <= 0) is not proof of a complete file: a server or proxy can close
            // the connection early without throwing, and without this check a short read passed
            // the magic-byte test whenever the truncation landed after the first few bytes.
            if (total != null && totalRead < total) {
                Log.w(TAG, "Download short: got $totalRead of $total bytes for ${candidate.url}")
                return AttemptOutcome.Retryable(
                    java.io.IOException("Download incomplete: received $totalRead of $total bytes")
                )
            }

            // Post-download magic-byte validation: ensure the file we just wrote is actually a
            // valid model file. Catches sparse-file / partial-write corruption, server misroutes,
            // and other ways the bytes can go wrong.
            val ext = target.name.substringAfterLast('.', "").lowercase()
            val firstBytes = ByteArray(16)
            val bytesRead = partial.inputStream().use { it.read(firstBytes) }
            // `> 0` let a 0-byte 200 response skip validation entirely and be renamed into
            // place as a "model".
            if (bytesRead <= 0 || !isValidMagicForExtension(ext, firstBytes.copyOf(bytesRead))) {
                val hexDump = firstBytes.take(8).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
                Log.w(TAG, "Magic-byte check failed for .$ext — first bytes: $hexDump (${candidate.url})")
                runCatching { partial.delete() }
                return AttemptOutcome.Retryable(
                    IllegalStateException(
                        "Downloaded file does not start like a .$ext model (first bytes: $hexDump). " +
                            "The server may have served the wrong content, or the download was corrupted."
                    )
                )
            }

            if (target.exists()) target.delete()
            val renamed = partial.renameTo(target)
            if (!renamed) {
                // renameTo() returns false silently on cross-partition moves or permission errors.
                Log.e(TAG, "Rename failed: ${partial.absolutePath} -> ${target.absolutePath}")
                runCatching { partial.delete() }
                return AttemptOutcome.Fatal(
                    IllegalStateException(
                        "Failed to move downloaded file to its final location: ${target.absolutePath}. " +
                            "Check available storage and permissions."
                    )
                )
            }
            Log.i(TAG, "Download complete: ${target.absolutePath}")
            return AttemptOutcome.Success(target)
        }
    }

    /**
     * The failure the user sees once every attempt is spent - one line, the last real reason, and
     * what to do about it. A bare "Connection reset" leaves them tapping Install again against the
     * same wall.
     */
    private fun exhaustedFailure(attempts: Int, cause: Throwable?): Throwable = java.io.IOException(
        "Download failed after $attempts attempts (${describeFailure(cause)}). " +
            "Check the connection; if huggingface.co keeps resetting, enable the HuggingFace " +
            "mirror in Settings -> Preferences -> Network, or use a proxy, then try again. " +
            "Bytes already downloaded are kept and will be resumed.",
        cause,
    )

    /** [cause] in one short line: the message when there is one, the exception type otherwise. */
    private fun describeFailure(cause: Throwable?): String {
        if (cause == null) return "connection failed"
        val message = cause.message?.trim()
        if (!message.isNullOrEmpty()) {
            // Some socket exceptions carry the whole stack story in the message; one line is
            // all the retry notice has room for.
            return message.lineSequence().first().take(160)
        }
        return cause::class.java.simpleName
    }

    /**
     * Copies [input] into [target], emitting the same [Progress] events as [download]. Used
     * to import a model file the user already has on-device (SAF file picker): there is
     * nothing to fetch or resume, just a local stream copy, but sharing [Progress] lets
     * callers register a finished import exactly like a finished download.
     *
     * The magic-byte check runs against [expectedExtension] on the FIRST bytes read, before
     * anything is written to disk — a mis-picked file fails immediately instead of after
     * copying gigabytes for nothing. [expectedExtension] is passed explicitly rather than
     * derived from [target]'s name, since the picker lets the user choose any file regardless
     * of its own claimed name/extension.
     */
    fun copyFromStream(
        input: InputStream,
        target: File,
        totalBytes: Long?,
        expectedExtension: String,
    ): Flow<Progress> = flow {
        target.parentFile?.mkdirs()
        val partial = File(target.absolutePath + ".partial")
        emit(Progress.Started(totalBytes))

        var invalidMagic = false
        val ioFailure: Throwable? = try {
            input.use { source ->
                partial.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    // InputStream.read(ByteArray) is only guaranteed to return at least one
                    // byte, not four — a network-backed SAF provider (Google Drive and
                    // similar) can hand back 1-3 bytes on the very first read. Accumulate
                    // across reads into a dedicated 4-byte buffer until either 4 bytes are
                    // in hand or the stream hits EOF, then validate once. Every byte read
                    // during accumulation is still written to sink below, so the copy stays
                    // byte-exact regardless of how the reads happen to chunk.
                    // 16 bytes, not 4: the litertlm / tflite check reads offset 4..7 (the
                    // FlatBuffer "TFL3" tag) and needs at least 8 bytes to say anything at all.
                    // With a 4-byte buffer the size guard was always false, so importing ANY
                    // .litertlm / .tflite / .task through the file picker failed 100% of the time.
                    val sniffBuf = ByteArray(MAGIC_SNIFF_BYTES)
                    var sniffLen = 0
                    var sniffed = false
                    var totalRead = 0L
                    while (true) {
                        val n = source.read(buf)
                        if (n <= 0) {
                            // EOF before 4 bytes accumulated (including a 0-byte file, where
                            // sniffLen stays 0): validate whatever was collected.
                            // isValidMagicForExtension rejects anything shorter than 4 bytes.
                            if (!sniffed) {
                                sniffed = true
                                if (!isValidMagicForExtension(expectedExtension, sniffBuf.copyOf(sniffLen))) {
                                    invalidMagic = true
                                }
                            }
                            break
                        }
                        sink.write(buf, 0, n)
                        totalRead += n
                        emit(Progress.Tick(totalRead, totalBytes))
                        if (!sniffed) {
                            val need = (sniffBuf.size - sniffLen).coerceAtMost(n)
                            System.arraycopy(buf, 0, sniffBuf, sniffLen, need)
                            sniffLen += need
                            if (sniffLen >= sniffBuf.size) {
                                sniffed = true
                                if (!isValidMagicForExtension(expectedExtension, sniffBuf)) {
                                    invalidMagic = true
                                    break
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Leaving the screen mid-import used to strand a multi-GB `.partial` in internal
            // storage: only the IOException branch cleaned up.
            runCatching { partial.delete() }
            throw e
        } catch (e: java.io.IOException) {
            e
        }

        if (invalidMagic) {
            Log.w(TAG, "Import rejected: first bytes are not a valid .$expectedExtension file")
            runCatching { partial.delete() }
            emit(Progress.Failed(IllegalArgumentException(
                "Selected file does not look like a valid .$expectedExtension model " +
                "(unexpected magic bytes)."
            )))
            return@flow
        }
        if (ioFailure != null) {
            Log.w(TAG, "Import read interrupted: ${ioFailure::class.simpleName}: ${ioFailure.message}")
            runCatching { partial.delete() }
            emit(Progress.Failed(java.io.IOException(
                "Import interrupted (${ioFailure::class.simpleName}: ${ioFailure.message}).",
                ioFailure,
            )))
            return@flow
        }

        if (target.exists()) target.delete()
        val renamed = partial.renameTo(target)
        if (!renamed) {
            Log.e(TAG, "Rename failed: ${partial.absolutePath} -> ${target.absolutePath}")
            runCatching { partial.delete() }
            emit(Progress.Failed(IllegalStateException(
                "Failed to move the imported file to its final location: ${target.absolutePath}."
            )))
            return@flow
        }
        Log.i(TAG, "Import complete: ${target.absolutePath}")
        emit(Progress.Done(target))
    }.flowOn(Dispatchers.IO)
}
