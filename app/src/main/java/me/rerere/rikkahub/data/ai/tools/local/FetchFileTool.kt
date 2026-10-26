package me.rerere.rikkahub.data.ai.tools.local

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val FETCH_FILE_DEFAULT_WAIT_SECONDS = 60
private const val FETCH_FILE_MAX_WAIT_SECONDS = 180
private const val FETCH_FILE_POLL_MS = 500L

/** One row of a [DownloadManager] query, as the tool needs it. */
internal data class DownloadSnapshot(
    val status: Int,
    val localUri: String?,
    val bytes: Long,
    val reason: Int,
    val title: String?,
)

/** Stable, model-facing name for a DownloadManager status code. */
internal fun downloadStatusName(status: Int): String = when (status) {
    DownloadManager.STATUS_PENDING -> "pending"
    DownloadManager.STATUS_RUNNING -> "running"
    DownloadManager.STATUS_PAUSED -> "paused"
    DownloadManager.STATUS_SUCCESSFUL -> "success"
    DownloadManager.STATUS_FAILED -> "failed"
    else -> "unknown"
}

/**
 * DownloadManager reports failures as integers; the model needs a reason it can act on. The
 * mapping is deliberately coarse — the exact code is kept in `detail` — but every branch names
 * something the model can retry differently.
 */
internal fun downloadFailureReason(reason: Int): String = when (reason) {
    DownloadManager.ERROR_CANNOT_RESUME -> "cannot_resume"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "device_not_found"
    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file_already_exists"
    DownloadManager.ERROR_FILE_ERROR -> "file_error"
    DownloadManager.ERROR_HTTP_DATA_ERROR -> "http_data_error"
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "insufficient_space"
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too_many_redirects"
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "unhandled_http_code"
    DownloadManager.ERROR_UNKNOWN -> "unknown"
    else -> "error_$reason"
}

/**
 * T-12 — download and wait.
 *
 * `download_file` queues and returns immediately; nothing in the surface could say whether the
 * file arrived. This tool closes that loop: it waits for the queue entry to reach a terminal
 * state, then reports the local uri and byte count, so "downloaded" means a file that exists.
 */
fun fetchFileTool(context: Context): Tool = Tool(
    name = "fetch_file",
    description = """
        Download a file and wait until it is on disk.

        Unlike download_file, which returns as soon as the transfer is queued, this tool waits
        for completion and then reports the local uri and byte count — "downloaded" means a file
        that exists. Pass download_id (from download_file or an earlier fetch_file) to keep
        waiting on a transfer that is still running instead of starting it again.

        Returns status = success | running | failed, plus checks (completed, readable,
        size_known) and, on failure, a reason such as insufficient_space or http_data_error.
        On running, call again with the same download_id to continue waiting. Files land in the
        public Downloads folder.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "http(s) URL to download; required unless download_id is given")
                })
                put("filename", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional filename (defaults to the URL's last path segment)")
                })
                put("download_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Wait on an existing download instead of starting a new one")
                })
                put("wait_seconds", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "How long to wait (0-180, default 60). 0 returns the current state immediately.",
                    )
                })
            },
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val existingId = params["download_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val waitSeconds = (params["wait_seconds"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: FETCH_FILE_DEFAULT_WAIT_SECONDS)
            .coerceIn(0, FETCH_FILE_MAX_WAIT_SECONDS)

        val dm = context.getSystemService(DownloadManager::class.java)
            ?: return@Tool fmTextPart(fmErrEnvelope("download_manager_unavailable", "DownloadManager is not available on this device"))

        val downloadId: Long
        val requestedName: String?
        if (existingId != null) {
            downloadId = existingId
            requestedName = null
        } else {
            val url = params["url"]?.jsonPrimitive?.contentOrNull?.trim()
            if (url.isNullOrBlank()) {
                return@Tool fmTextPart(
                    fmErrEnvelope(
                        "missing_url",
                        "provide url, or download_id to keep waiting on an existing download",
                    )
                )
            }
            // Same rule as download_file: DownloadManager bypasses the OkHttp egress guard, so a
            // private/loopback target must be refused before it is queued.
            if (me.rerere.rikkahub.data.ai.net.isBlockedDownloadUrl(url)) {
                return@Tool fmTextPart(
                    fmErrEnvelope(
                        "blocked_private_address",
                        "Downloads from loopback, private or link-local addresses are refused.",
                    )
                )
            }
            val uri = runCatching { url.toUri() }.getOrNull()
                ?: return@Tool fmTextPart(fmErrEnvelope("bad_url", "url could not be parsed"))
            val rawName = params["filename"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment
                ?: "download_${System.currentTimeMillis()}"
            val name = rawName.substringAfterLast('/').trimStart('.').ifEmpty {
                "download_${System.currentTimeMillis()}"
            }
            requestedName = name
            val request = DownloadManager.Request(uri)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .setTitle(name)
            downloadId = runCatching { dm.enqueue(request) }.getOrElse { error ->
                return@Tool fmTextPart(
                    buildJsonObject {
                        put("error", "enqueue_failed")
                        put("detail", error.message?.take(200) ?: "DownloadManager rejected the request")
                        put("recovery", "Check the filename and the URL, or use download_file for the queue-only path.")
                    }.toString()
                )
            }
        }

        val deadline = System.currentTimeMillis() + waitSeconds * 1_000L
        var snapshot = queryDownload(dm, downloadId)
        while (
            snapshot != null &&
            snapshot.status != DownloadManager.STATUS_SUCCESSFUL &&
            snapshot.status != DownloadManager.STATUS_FAILED &&
            System.currentTimeMillis() < deadline
        ) {
            delay(FETCH_FILE_POLL_MS)
            snapshot = queryDownload(dm, downloadId)
        }

        if (snapshot == null) {
            return@Tool fmTextPart(
                buildJsonObject {
                    put("error", "download_not_found")
                    put("download_id", downloadId)
                    put(
                        "recovery",
                        "The queue entry no longer exists — it may have been cleared. Start a new download with url.",
                    )
                }.toString()
            )
        }

        val status = downloadStatusName(snapshot.status)
        val completed = snapshot.status == DownloadManager.STATUS_SUCCESSFUL
        val readable = completed && !snapshot.localUri.isNullOrBlank()
        val payload = buildJsonObject {
            put("download_id", downloadId)
            put("status", status)
            requestedName?.let { put("filename", it) }
            snapshot.title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
            snapshot.localUri?.takeIf { it.isNotBlank() }?.let { put("local_uri", it) }
            if (snapshot.bytes >= 0) put("bytes", snapshot.bytes)
            if (snapshot.status == DownloadManager.STATUS_FAILED) {
                put("reason", downloadFailureReason(snapshot.reason))
                put("detail", "DownloadManager error code ${snapshot.reason}")
            }
            putChecks(
                listOf(
                    ResultCheck("completed", completed, "status=$status"),
                    ResultCheck(
                        "readable",
                        readable,
                        if (readable) "local uri available" else "no readable local uri yet",
                    ),
                    ResultCheck(
                        "size_known",
                        snapshot.bytes > 0,
                        if (snapshot.bytes > 0) "${snapshot.bytes} bytes" else "size not reported",
                    ),
                )
            )
            if (status == "running" || status == "pending" || status == "paused") {
                put(
                    "next",
                    "Still transferring. Call fetch_file again with download_id=$downloadId to keep waiting.",
                )
            }
            if (status == "failed") {
                put(
                    "next",
                    "Nothing was written. Retry with a different url or filename; if the reason is " +
                        "insufficient_space, free storage first.",
                )
            }
        }
        fmTextPart(payload.toString())
    },
)

/** Reads the queue row, or null when DownloadManager no longer knows the id. */
private fun queryDownload(dm: DownloadManager, id: Long): DownloadSnapshot? {
    val cursor = runCatching {
        dm.query(DownloadManager.Query().setFilterById(id))
    }.getOrNull() ?: return null
    return cursor.use {
        if (!it.moveToFirst()) return null
        fun column(name: String): Int = it.getColumnIndex(name)
        val statusIndex = column(DownloadManager.COLUMN_STATUS)
        val uriIndex = column(DownloadManager.COLUMN_LOCAL_URI)
        val bytesIndex = column(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val reasonIndex = column(DownloadManager.COLUMN_REASON)
        val titleIndex = column(DownloadManager.COLUMN_TITLE)
        DownloadSnapshot(
            status = if (statusIndex >= 0) it.getInt(statusIndex) else DownloadManager.STATUS_PENDING,
            localUri = if (uriIndex >= 0) it.getString(uriIndex) else null,
            bytes = if (bytesIndex >= 0) it.getLong(bytesIndex) else -1L,
            reason = if (reasonIndex >= 0) it.getInt(reasonIndex) else 0,
            title = if (titleIndex >= 0) it.getString(titleIndex) else null,
        )
    }
}
