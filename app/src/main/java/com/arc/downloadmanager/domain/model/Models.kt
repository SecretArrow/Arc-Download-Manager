package com.arc.downloadmanager.domain.model

/** Persistent states of a download task. */
enum class DownloadStatus {
    QUEUED,
    CONNECTING,
    DOWNLOADING,
    PAUSING,
    PAUSED,
    MERGING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELED,
    WAITING_NETWORK,
    WAITING_STORAGE,
    AUTH_REQUIRED,
    ;

    val isActive: Boolean
        get() = this == CONNECTING || this == DOWNLOADING || this == PAUSING || this == MERGING || this == VERIFYING

    val isRunningCandidate: Boolean
        get() = this == QUEUED || this == WAITING_NETWORK || this == WAITING_STORAGE

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELED
}

/** Download scheduling priority. Higher value starts earlier. */
enum class Priority(val value: Int) {
    LOW(0),
    NORMAL(1),
    HIGH(2),
    ;

    companion object {
        fun fromValue(value: Int): Priority = entries.firstOrNull { it.value == value } ?: NORMAL
    }
}

/** Where the download originated from. */
enum class DownloadSource {
    MANUAL,
    BROWSER,
    BATCH,
}

/** Destination abstraction for finalized files. */
sealed interface Destination {
    /** System Downloads collection via MediaStore (API 29+) or public Downloads dir on older versions. */
    data object Default : Destination

    /** User-chosen SAF tree. [treeUri] is a persisted content URI. */
    data class CustomFolder(val treeUri: String) : Destination
}

/** Request to enqueue a new download. */
data class DownloadRequest(
    val url: String,
    val filename: String? = null,
    val destination: Destination = Destination.Default,
    val segments: Int = 0,
    val priority: Priority = Priority.NORMAL,
    val wifiOnly: Boolean? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookies: String? = null,
    val checksum: String? = null,
    val source: DownloadSource = DownloadSource.MANUAL,
    /** Content-Disposition reported by WebView's DownloadListener, if any. */
    val contentDisposition: String? = null,
    /** MIME type reported by WebView's DownloadListener, if any. */
    val mimeType: String? = null,
)

/** Aggregated counters shown on Home. */
data class QueueStats(
    val active: Int = 0,
    val queued: Int = 0,
    val failed: Int = 0,
    val completed: Int = 0,
    val totalBytesDownloaded: Long = 0,
    val totalBytesAll: Long = 0,
)

/** A single byte range assigned to a segment. */
data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long
        get() = endInclusive - start + 1
}

/** Header info discovered while probing a URL. */
data class ResourceInfo(
    val url: String,
    val statusCode: Int,
    val contentLength: Long,
    val acceptRanges: Boolean,
    val mimeType: String?,
    val filename: String?,
    val eTag: String?,
    val lastModified: String?,
)
