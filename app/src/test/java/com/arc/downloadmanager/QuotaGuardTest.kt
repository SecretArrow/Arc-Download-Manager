package com.arc.downloadmanager

import com.arc.downloadmanager.service.QuotaGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-logic tests for the Android 15+ dataSync foreground-service quota guard.
 * The platform allows 6 hours of dataSync runtime per rolling 24-hour window.
 */
class QuotaGuardTest {
    private val window = QuotaGuard.WINDOW_MS

    @Test
    fun `no recorded timeout is never exhausted`() {
        assertFalse(QuotaGuard.isExhaustedAt(0L, 1_000L))
        assertFalse(QuotaGuard.isExhaustedAt(-1L, 1_000L))
    }

    @Test
    fun `within 24 hour window counts as exhausted`() {
        val now = 1_000_000_000_000L
        assertTrue(QuotaGuard.isExhaustedAt(now - 1, now))
        assertTrue(QuotaGuard.isExhaustedAt(now - window / 2, now))
        assertTrue(QuotaGuard.isExhaustedAt(now - window + 1, now))
    }

    @Test
    fun `beyond 24 hour window is no longer exhausted`() {
        val now = 1_000_000_000_000L
        assertFalse(QuotaGuard.isExhaustedAt(now - window, now))
        assertFalse(QuotaGuard.isExhaustedAt(now - window - 1, now))
    }

    @Test
    fun `future timestamps are ignored`() {
        val now = 10_000_000L
        assertFalse(QuotaGuard.isExhaustedAt(now + 1, now))
    }
}
