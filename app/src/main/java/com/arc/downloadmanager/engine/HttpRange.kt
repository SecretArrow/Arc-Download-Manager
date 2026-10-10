package com.arc.downloadmanager.engine

import java.util.Locale

/**
 * Parses HTTP `Content-Range` headers, e.g. `bytes 100-999/1234` or `bytes 100-999/unknown-total`.
 */
object ContentRangeParser {

    /** Returns (start, endInclusive) or null when the header is missing or malformed. */
    fun parse(header: String?): Pair<Long, Long>? {
        if (header.isNullOrBlank()) return null
        val value = header.trim().lowercase(Locale.US)
        if (!value.startsWith("bytes")) return null
        val rangePart = value.substringAfter("bytes").trim()
        val withoutTotal = rangePart.substringBefore('/')
        val parts = withoutTotal.split("-")
        if (parts.size != 2) return null
        val start = parts[0].trim().toLongOrNull() ?: return null
        val end = parts[1].trim().toLongOrNull() ?: return null
        if (start < 0 || end < start) return null
        return start to end
    }

    /** Extracts total size from `Content-Range: bytes x-y/total`, or -1 when unknown. */
    fun totalSize(header: String?): Long {
        if (header.isNullOrBlank()) return -1
        val totalPart = header.trim().substringAfter('/', "")
        if (totalPart == "*") return -1
        return totalPart.toLongOrNull() ?: -1
    }
}

/**
 * Detects whether a server supports byte-range requests based on response headers.
 */
object RangeSupportDetector {
    private const val ACCEPT_RANGES = "accept-ranges"

    fun supports(headers: Map<String, List<String>>, statusCode: Int): Boolean {
        if (statusCode == 206) return true
        val acceptRanges = headers.entries
            .firstOrNull { it.key.equals(ACCEPT_RANGES, ignoreCase = true) }
            ?.value?.firstOrNull()
            ?.lowercase(Locale.US)
        return acceptRanges != null && acceptRanges.contains("bytes")
    }
}
