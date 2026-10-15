package com.arc.downloadmanager.engine

import kotlinx.coroutines.delay

/**
 * Token-bucket style bandwidth limiter applied per connection.
 * A limit of 0 or less means unlimited. Coroutine-friendly (no thread blocking).
 *
 * The effective rate can be supplied lazily via [limitProvider] so long-lived transfers
 * consult the CURRENT limit on every throttle check (per-network speed limits switch
 * while a download runs). The single-value constructor keeps the classic fixed-rate
 * behavior for tests and simple callers.
 */
class BandwidthLimiter(private val limitProvider: () -> Long) {
    constructor(bytesPerSecond: Long) : this({ bytesPerSecond })

    private var limitBytes: Long = limitProvider().coerceAtLeast(0L)
    private var bucket: Long = if (limitBytes > 0) limitBytes / 2 else 0
    private var lastRefill: Long = System.nanoTime()

    /** Accounts for [bytes] consumed, suspending when the configured limit is exceeded. */
    suspend fun onBytes(bytes: Int) {
        if (bytes <= 0) return
        while (true) {
            synchronized(this) {
                val newLimit = limitProvider().coerceAtLeast(0L)
                if (newLimit != limitBytes) {
                    // The effective limit changed (user edit / Wi-Fi <-> cellular switch):
                    // restart the bucket against the new rate.
                    limitBytes = newLimit
                    bucket = if (limitBytes > 0) limitBytes / 2 else 0
                    lastRefill = System.nanoTime()
                }
                if (limitBytes <= 0) return
                val now = System.nanoTime()
                val refill = ((now - lastRefill).toDouble() / 1_000_000_000.0 * limitBytes).toLong()
                if (refill > 0) {
                    // Bucket capacity never falls below a single read chunk, otherwise an
                    // oversized read could never be granted and the loop would spin forever.
                    bucket = minOf(maxOf(limitBytes, bytes.toLong()), bucket + refill)
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
