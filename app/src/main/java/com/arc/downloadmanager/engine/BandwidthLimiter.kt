package com.arc.downloadmanager.engine

import kotlinx.coroutines.delay

/**
 * Token-bucket style bandwidth limiter applied per connection.
 * A limit of 0 or less means unlimited. Coroutine-friendly (no thread blocking).
 */
class BandwidthLimiter(private val bytesPerSecond: Long) {

    private var bucket: Long = if (bytesPerSecond > 0) bytesPerSecond / 2 else 0
    private var lastRefill: Long = System.nanoTime()

    /** Accounts for [bytes] consumed, suspending when the configured limit is exceeded. */
    suspend fun onBytes(bytes: Int) {
        if (bytesPerSecond <= 0 || bytes <= 0) return
        while (true) {
            synchronized(this) {
                val now = System.nanoTime()
                val refill = ((now - lastRefill).toDouble() / 1_000_000_000.0 * bytesPerSecond).toLong()
                if (refill > 0) {
                    bucket = minOf(bytesPerSecond, bucket + refill)
                    lastRefill = now
                }
                if (bucket >= bytes) {
                    bucket -= bytes
                    return
                }
            }
            delay(20)
        }
    }
}
