package com.arc.downloadmanager.engine

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with jitter for retryable failures.
 */
class BackoffPolicy(
    private val baseDelayMs: Long = 2_000L,
    private val maxDelayMs: Long = 10L * 60L * 1000L,
    private val random: Random = Random.Default,
) {
    /** Milliseconds to wait before the [attempt]-th retry (1-based). Honors Retry-After when present. */
    fun delayFor(
        attempt: Int,
        retryAfterSeconds: Long? = null,
    ): Long {
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            return min(retryAfterSeconds * 1000L, maxDelayMs)
        }
        val exp = baseDelayMs * 2.0.pow((attempt - 1).coerceAtLeast(0))
        val capped = min(exp.toLong(), maxDelayMs)
        val jitter = (capped * 0.2 * random.nextDouble()).toLong()
        return capped + jitter
    }
}
