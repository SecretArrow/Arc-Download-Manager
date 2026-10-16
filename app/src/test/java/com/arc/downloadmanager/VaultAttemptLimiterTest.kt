package com.arc.downloadmanager

import com.arc.downloadmanager.data.vault.VaultAttemptLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultAttemptLimiterTest {
    private val t0 = 1_000_000L

    @Test
    fun `first four failures are free`() {
        var fails = 0
        repeat(4) {
            assertEquals(0L, VaultAttemptLimiter.onFail(t0, fails))
            fails++
            val d = VaultAttemptLimiter.decide(t0, fails, 0L)
            assertTrue(d.allowed)
        }
        assertEquals(VaultAttemptLimiter.FREE_ATTEMPTS, fails)
    }

    @Test
    fun `fifth failure locks for base window`() {
        val lockedUntil = VaultAttemptLimiter.onFail(t0, 4)
        assertEquals(t0 + VaultAttemptLimiter.BASE_LOCK_MS, lockedUntil)
        val d = VaultAttemptLimiter.decide(t0 + 1000, 5, lockedUntil)
        assertFalse(d.allowed)
        assertEquals(VaultAttemptLimiter.BASE_LOCK_MS - 1000, d.waitMs)
    }

    @Test
    fun `wait doubles per extra failure and is capped`() {
        val after5 = VaultAttemptLimiter.onFail(t0, 4)
        val after6 = VaultAttemptLimiter.onFail(t0, 5)
        assertEquals(VaultAttemptLimiter.BASE_LOCK_MS * 2, after6 - t0)
        val after9 = VaultAttemptLimiter.onFail(t0, 8)
        assertEquals(VaultAttemptLimiter.BASE_LOCK_MS * 16, after9 - t0)
        val after20 = VaultAttemptLimiter.onFail(t0, 19)
        assertEquals(VaultAttemptLimiter.MAX_LOCK_MS, after20 - t0)
    }

    @Test
    fun `lockout expires after the wait`() {
        val lockedUntil = VaultAttemptLimiter.onFail(t0, 4)
        assertFalse(VaultAttemptLimiter.decide(lockedUntil - 1, 5, lockedUntil).allowed)
        assertTrue(VaultAttemptLimiter.decide(lockedUntil, 5, lockedUntil).allowed)
    }

    @Test
    fun `attempts left reported while free`() {
        assertEquals(4, VaultAttemptLimiter.decide(t0, 0, 0).attemptsLeft)
        assertEquals(1, VaultAttemptLimiter.decide(t0, 3, 0).attemptsLeft)
        assertEquals(0, VaultAttemptLimiter.decide(t0, 4, 0).attemptsLeft)
    }

    @Test
    fun `attempts left never negative`() {
        assertEquals(0, VaultAttemptLimiter.decide(t0, 9, 0).attemptsLeft)
    }
}
