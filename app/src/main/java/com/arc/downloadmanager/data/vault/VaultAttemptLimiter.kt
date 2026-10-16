package com.arc.downloadmanager.data.vault

/**
 * Pure, testable brute-force throttle for vault unlock attempts.
 *
 * Policy: the first 4 failures are free; failure #5 locks the vault for
 * 30 seconds, and every further failure doubles the wait (capped at 15
 * minutes). A successful unlock resets the counter. Timestamps are injected
 * so the policy is deterministic under test; persistence happens in the
 * envelope store.
 */
object VaultAttemptLimiter {
    const val FREE_ATTEMPTS = 4
    const val BASE_LOCK_MS = 30_000L
    const val MAX_LOCK_MS = 15 * 60_000L

    data class Decision(
        val allowed: Boolean,
        /** Remaining wait in ms when [allowed] is false. */
        val waitMs: Long = 0L,
        /** Attempts left before the next lockout kicks in. */
        val attemptsLeft: Int = 0,
    )

    fun decide(
        nowMs: Long,
        failCount: Int,
        lockedUntilMs: Long,
    ): Decision {
        if (lockedUntilMs > nowMs) return Decision(false, lockedUntilMs - nowMs, 0)
        val left = (FREE_ATTEMPTS - failCount).coerceAtLeast(0)
        return Decision(true, 0L, left)
    }

    /** Called after a failed attempt; returns the new locked-until timestamp (0 = none). */
    fun onFail(
        nowMs: Long,
        failCount: Int,
    ): Long {
        val newCount = failCount + 1
        if (newCount <= FREE_ATTEMPTS) return 0L
        val exponent = newCount - FREE_ATTEMPTS
        val wait = (BASE_LOCK_MS shl (exponent - 1).coerceAtMost(10)).coerceAtMost(MAX_LOCK_MS)
        return nowMs + wait
    }
}
