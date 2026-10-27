package me.rerere.locallm

/**
 * Where a model file may be fetched from, and how hard it is worth trying.
 *
 * The failures users report are not "the model does not exist" - they are a connection reset
 * seconds in, or a transfer that goes silent halfway. Both are transient by nature and both were
 * terminal before: [ModelInstall.download] made exactly one request, so a reset 400 MB into a
 * 600 MB file threw away the tap, and a stalled body looked identical to a slow one for the full
 * ten minutes of the shared client's read timeout.
 *
 * Keeping the *rules* here (pure, no socket) is what lets them be tested: which hosts to try, in
 * which order, which HTTP codes are worth another attempt, how long to wait before it.
 *
 * ## The mirror
 *
 * `huggingface.co` and its `cdn-lfs` hosts are unreachable from a fair share of mainland Chinese
 * networks - the handshake completes and the first body bytes are reset, or DNS answers are
 * poisoned. `hf-mirror.com` serves the same `/<repo>/resolve/<rev>/<file>` paths from inside the
 * country, so a URL only has to change host to become reachable. Whether the mirror is used at
 * all is the caller's decision (a user setting), because it is a third-party host; when it is off
 * a download behaves exactly as it did before.
 *
 * Only hub URLs are rewritten. A pasted URL pointing anywhere else is fetched as given, and a
 * pasted *mirror* URL falls back to the primary host - the same rule, read the other way round.
 */
object ModelDownloadPolicy {

    /** Attempts per download, including the first one. Four covers reset → mirror → reset → mirror. */
    const val MAX_ATTEMPTS = 4

    /**
     * Seconds the transfer may go without a single byte before the attempt is abandoned.
     *
     * This is a *silence* budget, not a total budget: a slow-but-alive connection reads
     * something every few seconds and is never interrupted. The shared OkHttp client waits ten
     * minutes between bytes, which is right for a long prefill and wrong here - that ten minutes
     * is exactly the "progress bar frozen" report.
     */
    const val STALL_TIMEOUT_SECONDS = 45L

    const val PRIMARY_HOST = "huggingface.co"
    const val MIRROR_HOST = "hf-mirror.com"

    /** One URL to try, with the host kept for the retry notice the UI shows. */
    data class Candidate(val url: String, val host: String) {
        val isMirror: Boolean get() = host.equals(MIRROR_HOST, ignoreCase = true)
    }

    /**
     * The URLs to try, in order: as given, then the other side of the huggingface.co /
     * hf-mirror.com pair when the URL is one of them and [mirrorEnabled]. One entry when neither
     * applies, so a plain third-party URL never gets silently redirected somewhere else.
     */
    fun candidates(url: String, mirrorEnabled: Boolean): List<Candidate> {
        val primary = Candidate(url = url, host = hostOf(url))
        if (!mirrorEnabled) return listOf(primary)

        val alternateUrl = when (primary.host) {
            PRIMARY_HOST -> withHost(url, MIRROR_HOST)
            MIRROR_HOST -> withHost(url, PRIMARY_HOST)
            else -> null
        } ?: return listOf(primary)

        return listOf(primary, Candidate(url = alternateUrl, host = hostOf(alternateUrl)))
    }

    /**
     * Whether a failed response is worth retrying. Server-side and throttling codes are; a 4xx
     * that names the request itself (404 unknown file, 403 private repo, 401 bad token) will
     * answer the same thing to the fourth attempt, and retrying it only delays the message the
     * user needs to see.
     */
    fun isTransientHttpCode(code: Int): Boolean =
        code == 408 || code == 425 || code == 429 || code in 500..599

    /**
     * Pause before attempt [attempt] (1-based; the first attempt has no pause).
     *
     * Short enough that a user does not think the tap was lost, long enough that a mirror or a
     * congested CDN gets a different connection rather than the same broken one.
     */
    fun backoffMillis(attempt: Int): Long = when {
        attempt <= 2 -> 1_000L
        attempt == 3 -> 2_000L
        else -> 4_000L
    }

    /**
     * The host of [url], lower-cased, without scheme, port, user info or path.
     *
     * Empty when there is no `scheme://` at all - everything that reaches the downloader is an
     * absolute https URL, and a bare path is not a "host" worth printing in a retry notice.
     */
    fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val rest = url.substring(schemeEnd + 3)
        return rest
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()
    }

    /**
     * [url] with its host replaced by [host], keeping the scheme, path and query intact.
     * Null when [url] has no authority to replace.
     */
    fun withHost(url: String, host: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val prefix = url.substring(0, schemeEnd + 3)
        val rest = url.substring(schemeEnd + 3)
        val pathStart = rest.indexOf('/')
        val authority = if (pathStart < 0) rest else rest.substring(0, pathStart)
        if (authority.isEmpty()) return null
        val tail = if (pathStart < 0) "" else rest.substring(pathStart)
        // Keep an explicit port when the original authority carried one (an IPv6 literal in
        // brackets is not a host we ever rewrite, so the simple split is enough here).
        val port = authority.substringAfter(':', "")
        val portSuffix = if (port.isNotEmpty() && authority.count { it == ':' } == 1) ":$port" else ""
        return prefix + host + portSuffix + tail
    }
}
