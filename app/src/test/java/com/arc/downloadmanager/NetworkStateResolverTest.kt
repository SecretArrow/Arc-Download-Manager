package com.arc.downloadmanager

import com.arc.downloadmanager.engine.NetworkStateResolver
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure connectivity decision core behind [com.arc.downloadmanager.engine.NetworkMonitor].
 *
 * Regression context: when the default network lies (null/stale mid-handoff on some ROMs) the
 * engine used to strand tasks in WAITING_NETWORK on cellular until the user toggled Wi-Fi —
 * downloads looked "Wi-Fi only" even with the Wi-Fi-only switch off.
 */
class NetworkStateResolverTest {
    @Test
    fun `usable default network always wins`() {
        val resolved = NetworkStateResolver.resolve(active = true to false, others = listOf(true to true))
        assertEquals(true to false, resolved)
    }

    @Test
    fun `null default falls back to any online network`() {
        val resolved = NetworkStateResolver.resolve(active = null, others = listOf(false to false, true to false))
        assertEquals(true to false, resolved)
    }

    @Test
    fun `offline default falls back to online network`() {
        val resolved = NetworkStateResolver.resolve(active = false to false, others = listOf(true to true))
        assertEquals(true to true, resolved)
    }

    @Test
    fun `fallback prefers wifi among usable networks`() {
        val resolved =
            NetworkStateResolver.resolve(
                active = null,
                others = listOf(true to false, false to false, true to true),
            )
        assertEquals(true to true, resolved)
    }

    @Test
    fun `no usable network resolves offline`() {
        val resolved =
            NetworkStateResolver.resolve(
                active = false to false,
                others = listOf(false to true, false to false),
            )
        assertEquals(false to false, resolved)
    }

    @Test
    fun `empty scan resolves offline`() {
        val resolved = NetworkStateResolver.resolve(active = null, others = emptyList())
        assertEquals(false to false, resolved)
    }
}
