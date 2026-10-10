package com.arc.downloadmanager.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Computes smoothed download speeds from progress samples. Kept in the UI layer so the
 * database is not hammered with speed snapshots.
 */
class SpeedTracker(
    private val windowMs: Long = 3_000L,
    private val smoothing: Double = 0.4,
) {
    data class Sample(val timeMs: Long, val bytes: Long)

    private data class History(val samples: ArrayDeque<Sample>, var ema: Long = 0L)

    private val histories = ConcurrentHashMap<String, History>()

    /** Records progress and returns the smoothed speed in bytes/sec (0 when unknown yet). */
    fun record(
        id: String,
        downloadedBytes: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val history = histories.getOrPut(id) { History(ArrayDeque()) }
        synchronized(history) {
            val last = history.samples.lastOrNull()
            if (last != null && nowMs > last.timeMs) {
                val instantBps = (downloadedBytes - last.bytes) * 1000 / (nowMs - last.timeMs)
                history.ema =
                    if (history.ema == 0L) {
                        instantBps.coerceAtLeast(0)
                    } else {
                        (smoothing * instantBps + (1 - smoothing) * history.ema).toLong().coerceAtLeast(0)
                    }
            }
            if (last == null || downloadedBytes < last.bytes || nowMs - last.timeMs > windowMs) {
                history.samples.addLast(Sample(nowMs, downloadedBytes))
            }
            while (history.samples.size > 2 && nowMs - history.samples.first().timeMs > windowMs * 2) {
                history.samples.removeFirst()
            }
            return history.ema
        }
    }

    fun forget(id: String) {
        histories.remove(id)
    }

    fun reset() {
        histories.clear()
    }
}
