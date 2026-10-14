package com.arc.downloadmanager.engine

/** Stable error codes surfaced to the UI with human-readable messages. */
enum class DownloadError(val isRecoverable: Boolean) {
    INVALID_URL(false),
    NETWORK_UNAVAILABLE(true),
    WIFI_REQUIRED(true),
    SERVER_ERROR(true),
    AUTH_REQUIRED(true),
    FORBIDDEN(true),
    EXPIRED_LINK(true),
    RANGE_NOT_SUPPORTED(true),
    RESOURCE_CHANGED(true),
    RATE_LIMITED(true),
    INSUFFICIENT_STORAGE(true),
    PERMISSION_DENIED(false),
    FILE_EXISTS(false),
    SEGMENT_MISMATCH(true),
    MERGE_FAILED(true),
    CHECKSUM_MISMATCH(false),
    PAGE_NOT_FILE(false),
    PROTOCOL_UNSUPPORTED(false),
    TORRENT_METADATA_TIMEOUT(true),
    TORRENT_FAILED(false),
    IO_ERROR(true),
    CANCELED(false),
    UNKNOWN(true),
}
