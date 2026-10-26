package me.rerere.rikkahub.data.ai.tools.local

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Test

class FetchFileToolTest {

    @Test
    fun `status codes get model-facing names`() {
        assertEquals("pending", downloadStatusName(DownloadManager.STATUS_PENDING))
        assertEquals("running", downloadStatusName(DownloadManager.STATUS_RUNNING))
        assertEquals("paused", downloadStatusName(DownloadManager.STATUS_PAUSED))
        assertEquals("success", downloadStatusName(DownloadManager.STATUS_SUCCESSFUL))
        assertEquals("failed", downloadStatusName(DownloadManager.STATUS_FAILED))
        assertEquals("unknown", downloadStatusName(999))
    }

    @Test
    fun `failure codes become reasons a model can act on`() {
        assertEquals(
            "insufficient_space",
            downloadFailureReason(DownloadManager.ERROR_INSUFFICIENT_SPACE),
        )
        assertEquals(
            "file_already_exists",
            downloadFailureReason(DownloadManager.ERROR_FILE_ALREADY_EXISTS),
        )
        assertEquals(
            "http_data_error",
            downloadFailureReason(DownloadManager.ERROR_HTTP_DATA_ERROR),
        )
        assertEquals(
            "too_many_redirects",
            downloadFailureReason(DownloadManager.ERROR_TOO_MANY_REDIRECTS),
        )
        assertEquals("unknown", downloadFailureReason(DownloadManager.ERROR_UNKNOWN))
        // An unmapped code still has to say something usable.
        assertEquals("error_12345", downloadFailureReason(12345))
    }
}
