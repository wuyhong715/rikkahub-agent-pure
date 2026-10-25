package me.rerere.rikkahub.data.sync.s3

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.sync.BackupItem

@Serializable
data class S3Config(
    val endpoint: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val bucket: String = "",
    val region: String = "auto",
    val pathStyle: Boolean = true,
    // Backed-up slices. New installs default to everything; a pre-granular `["DATABASE","FILES"]`
    // still decodes because BackupItem keeps the legacy FILES value, and is expanded on the next
    // write by BackupItem.normalize.
    val items: List<BackupItem> = BackupItem.selectable,
) {
    val host: String
        get() = endpoint.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            // A reverse-proxied endpoint often carries a path ("https://host.com:8080/s3"). A Host
            // header (and the signed canonical host) must be host:port only — a path in there is
            // either rejected or silently re-signed against a different value.
            .substringBefore('/')
            .trimEnd('/')

    val isHttps: Boolean
        get() = endpoint.startsWith("https://")

    /**
     * The path part of [endpoint] ("" when there is none) — e.g. "/s3" for a reverse-proxied
     * endpoint like `https://host:8080/s3`.
     *
     * [host] strips it (a Host header must be host:port), so the signer has to put it back into
     * the canonical URI and the request URL. Without this every request went to the root path and
     * a sub-path deployment answered 404.
     */
    val basePath: String
        get() {
            val withoutScheme = endpoint.trim()
                .removePrefix("https://")
                .removePrefix("http://")
            val slash = withoutScheme.indexOf('/')
            if (slash < 0) return ""
            return withoutScheme.substring(slash).trimEnd('/').takeIf { it.isNotEmpty() } ?: ""
        }

    fun bucketUrl(): String {
        return if (pathStyle) {
            "${endpoint.trimEnd('/')}/$bucket"
        } else {
            val scheme = if (isHttps) "https://" else "http://"
            "$scheme$bucket.$host$basePath"
        }
    }
}
