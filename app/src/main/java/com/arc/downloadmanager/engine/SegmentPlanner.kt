package com.arc.downloadmanager.engine

import com.arc.downloadmanager.domain.model.ByteRange
import kotlin.math.ceil

/**
 * Plans contiguous, non-overlapping byte ranges for multi-segment downloads.
 * Segments are at least [MIN_SEGMENT_SIZE] bytes unless the file is tiny, so that
 * small files never spawn excessive connections.
 */
object SegmentPlanner {

    const val MIN_SEGMENT_SIZE: Long = 1024L * 1024L // 1 MiB
    const val AUTO_THRESHOLD: Long = 8L * 1024L * 1024L // files < 8 MiB: single stream

    /** Returns a list of ranges covering 0 until totalBytes, or an empty list when segmentation is not beneficial. */
    fun plan(totalBytes: Long, maxSegments: Int): List<ByteRange> {
        require(totalBytes > 0) { "totalBytes must be positive" }
        require(maxSegments >= 1) { "maxSegments must be >= 1" }
        if (totalBytes < AUTO_THRESHOLD || maxSegments <= 1) return listOf(ByteRange(0, totalBytes - 1))

        val segmentCount = minOf(maxSegments.toLong(), totalBytes / MIN_SEGMENT_SIZE).toInt().coerceAtLeast(1)
        val baseSize = totalBytes / segmentCount
        val remainder = totalBytes % segmentCount
        val ranges = ArrayList<ByteRange>(segmentCount)
        var cursor = 0L
        for (i in 0 until segmentCount) {
            val size = baseSize + if (i < remainder) 1 else 0
            ranges += ByteRange(cursor, cursor + size - 1)
            cursor += size
        }
        check(cursor == totalBytes) { "Ranges must cover the whole file" }
        return ranges
    }

    /** True when every byte between 0 and total-1 is covered exactly once. */
    fun isCoverageComplete(ranges: List<ByteRange>, totalBytes: Long): Boolean {
        if (ranges.isEmpty()) return false
        val sorted = ranges.sortedBy { it.start }
        if (sorted.first().start != 0L) return false
        var expected = 0L
        for (r in sorted) {
            if (r.start != expected) return false
            if (r.length <= 0) return false
            expected = r.endInclusive + 1
        }
        return expected == totalBytes
    }

    /** Estimates the resulting segment count for the given parameters (used by the UI). */
    fun estimateSegments(totalBytes: Long, maxSegments: Int): Int =
        if (totalBytes <= 0 || totalBytes < AUTO_THRESHOLD || maxSegments <= 1) {
            1
        } else {
            minOf(maxSegments.toLong(), ceil(totalBytes / MIN_SEGMENT_SIZE.toDouble()).toLong()).toInt().coerceAtLeast(1)
        }
}
