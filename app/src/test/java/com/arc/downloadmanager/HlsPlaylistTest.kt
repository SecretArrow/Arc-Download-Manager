package com.arc.downloadmanager

import com.arc.downloadmanager.engine.hls.HlsPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parser tests for the HLS (m3u8) playlist support in the download engine. */
class HlsPlaylistTest {
    @Test
    fun masterPlaylist_picksHighestBandwidthVariantAndResolvesItsUri() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360
            video-low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4128000,RESOLUTION=1920x1080
            video-high.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1280x720
            video-mid.m3u8
        """.trimIndent()
        val playlist = HlsPlaylist.parse(text)
        assertTrue(playlist.isMaster)
        assertTrue(playlist.segments.isEmpty())
        assertEquals("video-high.m3u8", playlist.nextPlaylistUri)
        val resolved = HlsPlaylist.resolve("https://cdn.example.com/hls/master.m3u8", playlist.nextPlaylistUri!!)
        assertEquals("https://cdn.example.com/hls/video-high.m3u8", resolved)
    }

    @Test
    fun playlistWithoutVariants_parsesAsEmptyMediaPlaylist() {
        val text = "#EXTM3U\n"
        val playlist = HlsPlaylist.parse(text)
        assertFalse(playlist.isMaster)
        assertTrue(playlist.segments.isEmpty())
    }

    @Test
    fun mediaPlaylist_parsesExtinfUriPairs() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXTINF:9.009,
            seg-1.ts
            #EXTINF:10.0,first chunk
            seg-2.ts
            #EXTINF:8.5,
            https://cdn.example.com/other/seg-3.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val playlist = HlsPlaylist.parse(text)
        assertFalse(playlist.isMaster)
        assertNull(playlist.nextPlaylistUri)
        assertEquals(3, playlist.segments.size)
        assertEquals("seg-1.ts", playlist.segments[0].uri)
        assertEquals(9.009, playlist.segments[0].durationSec, 1e-9)
        assertEquals("seg-2.ts", playlist.segments[1].uri)
        assertEquals(10.0, playlist.segments[1].durationSec, 1e-9)
        assertEquals("https://cdn.example.com/other/seg-3.ts", playlist.segments[2].uri)
        assertEquals(8.5, playlist.segments[2].durationSec, 1e-9)
    }

    @Test
    fun resolve_absolutizesRelativeUrisAgainstThePlaylistBase() {
        assertEquals(
            "https://cdn.example.com/vod/seg-1.ts",
            HlsPlaylist.resolve("https://cdn.example.com/vod/index.m3u8", "seg-1.ts"),
        )
        assertEquals(
            "https://cdn.example.com/vod/chunks/seg-2.ts",
            HlsPlaylist.resolve("https://cdn.example.com/vod/index.m3u8", "chunks/seg-2.ts"),
        )
        assertEquals(
            "https://cdn.example.com/seg-3.ts",
            HlsPlaylist.resolve("https://cdn.example.com/vod/index.m3u8", "../seg-3.ts"),
        )
        assertEquals(
            "https://other.example.com/seg-4.ts",
            HlsPlaylist.resolve("https://cdn.example.com/vod/index.m3u8", "https://other.example.com/seg-4.ts"),
        )
    }

    @Test
    fun resolve_fallsBackToBaseWhenUrisAreUnparseable() {
        assertEquals("https://bad url/", HlsPlaylist.resolve("https://bad url/", "seg.ts"))
    }

    @Test
    fun encryptedPlaylist_yieldsEmptySegments() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x9c7db8778570d05c3177c349fd9236aa
            #EXTINF:9.009,
            seg-1.ts
        """.trimIndent()
        val playlist = HlsPlaylist.parse(text)
        assertFalse(playlist.isMaster)
        assertTrue(playlist.segments.isEmpty())
        assertNull(playlist.nextPlaylistUri)
    }

    @Test
    fun methodNoneKeyPlaylist_isTreatedAsUnencrypted() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:9.009,
            seg-1.ts
        """.trimIndent()
        val playlist = HlsPlaylist.parse(text)
        assertEquals(1, playlist.segments.size)
        assertEquals("seg-1.ts", playlist.segments[0].uri)
    }

    @Test
    fun segmentCount_isCappedAt5000() {
        val sb = StringBuilder("#EXTM3U\n")
        repeat(5001) { index ->
            sb.append("#EXTINF:1.0,\n")
            sb.append("seg-$index.ts\n")
        }
        val playlist = HlsPlaylist.parse(sb.toString())
        assertEquals(5000, playlist.segments.size)
    }
}
