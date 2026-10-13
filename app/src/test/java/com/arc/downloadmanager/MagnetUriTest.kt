package com.arc.downloadmanager

import com.arc.downloadmanager.engine.MagnetUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetUriTest {
    private val hexMagnet = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=Ubuntu%2024.04%20LTS.iso"
    private val base32Hash = "OQU2RB4OOQVDSU5EQLZZPEWXV2Iozzpc".uppercase()

    @Test
    fun `parses v1 hex info hash and display name`() {
        val magnet = MagnetUri.parse(hexMagnet)!!
        assertEquals("0123456789abcdef0123456789abcdef01234567", magnet.infoHashHex)
        assertEquals("Ubuntu 24.04 LTS.iso", magnet.displayName)
        assertFalse(magnet.hasV2Hash)
        assertTrue(MagnetUri.isMagnet(hexMagnet))
    }

    @Test
    fun `parses base32 v1 info hash to hex`() {
        // 32 base32 chars decode to the same 20 bytes as a 40-char hex hash.
        val magnet = MagnetUri.parse("magnet:?xt=urn:btih:$base32Hash&dn=test")!!
        assertEquals("7429a8878e742a3953a482f39792d7ae90ece5e2", magnet.infoHashHex)
    }

    @Test
    fun `recognizes v2 btmh magnets`() {
        val v2 = "magnet:?xt=urn:btmh:1220caf1e1c30e81cb361b9ee167c4a76c1e1d5e4f4f4f4f4f4f4f4f4f4f4f4f4f4f&dn=v2"
        val magnet = MagnetUri.parse(v2)!!
        assertTrue(magnet.hasV2Hash)
        assertNull(magnet.infoHashHex)
        assertTrue(MagnetUri.isMagnet(v2))
    }

    @Test
    fun `rejects malformed magnets`() {
        assertNull(MagnetUri.parse(null))
        assertNull(MagnetUri.parse("magnet:?dn=no-hash"))
        assertNull(MagnetUri.parse("magnet:"))
        assertNull(MagnetUri.parse("https://example.com/file.zip"))
        assertFalse(MagnetUri.isMagnet("magnet:?dn=no-hash"))
    }

    @Test
    fun `unknown hash encodings are passed through verbatim`() {
        // The engine stays permissive at enqueue time; libtorrent is the hash authority.
        assertEquals("short", MagnetUri.parse("magnet:?xt=urn:btih:short")?.infoHashHex)
        val longGarbage = "z".repeat(40)
        assertEquals(longGarbage, MagnetUri.parse("magnet:?xt=urn:btih:$longGarbage")?.infoHashHex)
    }

    @Test
    fun `display name helper returns dn`() {
        assertEquals("Ubuntu 24.04 LTS.iso", MagnetUri.displayName(hexMagnet))
        assertNull(MagnetUri.displayName("magnet:?xt=urn:btih:${"a".repeat(40)}"))
    }

    @Test
    fun `infoHashHex helper works for hybrid magnets`() {
        // Hybrid magnet carries both v1 and v2 hashes; the v1 one wins for lookups.
        val hybrid =
            "magnet:?xt=urn:btmh:1220caf1e1c30e81cb361b9ee167c4a76c1e1d5e4f4f4f4f4f4f4f4f4f4f4f4f4f4f" +
                "&xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        assertEquals("0123456789abcdef0123456789abcdef01234567", MagnetUri.infoHashHex(hybrid))
    }

    @Test
    fun `leading and trailing whitespace is tolerated`() {
        assertEquals("0123456789abcdef0123456789abcdef01234567", MagnetUri.parse("  $hexMagnet  ")?.infoHashHex)
    }
}
