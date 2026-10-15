package com.arc.downloadmanager

import com.arc.downloadmanager.engine.hls.HlsKeyInfo
import com.arc.downloadmanager.engine.hls.HlsPlaylist
import com.arc.downloadmanager.engine.hls.parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the HLS/M3U8 playlist parser (engine/hls/HlsPlaylist.kt). */
class HlsPlaylistTest {
    private val base = "http://cdn.test/vod/index.m3u8"

    @Test
    fun `master playlist parses variants with quoted codecs and resolution`() {
        val text =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
            720/playlist.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            1080/playlist.m3u8
            """.trimIndent()

        val parsed = parse(text, base) as HlsPlaylist.Master

        assertEquals(2, parsed.variants.size)
        val low = parsed.variants[0]
        assertEquals("http://cdn.test/vod/720/playlist.m3u8", low.url)
        assertEquals(1_280_000L, low.bandwidth)
        assertEquals("1280x720", low.resolution)
        // The comma inside the quoted CODECS value must survive attribute splitting.
        assertEquals("avc1.64001f,mp4a.40.2", low.codecs)
        val high = parsed.variants[1]
        assertEquals("http://cdn.test/vod/1080/playlist.m3u8", high.url)
        assertEquals(2_560_000L, high.bandwidth)
    }

    @Test
    fun `variant URI attribute form is tolerated`() {
        val text =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=900000,URI="low.m3u8"
            """.trimIndent()

        val parsed = parse(text, base) as HlsPlaylist.Master

        assertEquals(1, parsed.variants.size)
        assertEquals("http://cdn.test/vod/low.m3u8", parsed.variants[0].url)
        assertEquals(900_000L, parsed.variants[0].bandwidth)
    }

    @Test
    fun `media playlist parses segments sequence target duration and endlist`() {
        val text =
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:2680
            #EXTINF:9.009,
            segment-2680.ts
            #EXTINF:9.009, first title ignored
            segment-2681.ts
            #EXT-X-ENDLIST
            """.trimIndent()

        val media = parse(text, base) as HlsPlaylist.Media

        assertEquals(2, media.segments.size)
        assertEquals(2680L, media.segments[0].sequence)
        assertEquals(2681L, media.segments[1].sequence)
        assertEquals(9.009, media.segments[0].durationSec, 1e-9)
        assertEquals(10L, media.targetDurationSec)
        assertEquals(18.018, media.totalDurationSec, 1e-9)
        assertFalse(media.isLive)
        assertNull(media.encryption)
        assertNull(media.initSegmentUrl)
    }

    @Test
    fun `relative and absolute urls resolve against the playlist url`() {
        val text =
            """
            #EXTM3U
            #EXTINF:4.0,
            seg/a.ts
            #EXTINF:4.0,
            http://other.test/absolute.ts
            #EXTINF:4.0,
            ../up.ts
            """.trimIndent()

        val media = parse(text, base) as HlsPlaylist.Media

        assertEquals("http://cdn.test/vod/seg/a.ts", media.segments[0].url)
        assertEquals("http://other.test/absolute.ts", media.segments[1].url)
        assertEquals("http://cdn.test/up.ts", media.segments[2].url)
    }

    @Test
    fun `aes-128 key with explicit iv is parsed`() {
        val text =
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0X9c7db8778570d05c3177c349fd9236aa
            #EXTINF:4.0,
            seg.ts
            #EXT-X-ENDLIST
            """.trimIndent()

        val media = parse(text, base) as HlsPlaylist.Media

        val key = media.encryption
        assertTrue(key != null)
        assertEquals("AES-128", key!!.method)
        assertEquals("http://cdn.test/vod/key.bin", key.keyUrl)
        // 0X prefix stripped, hex case preserved.
        assertEquals("9c7db8778570d05c3177c349fd9236aa", key.ivHex)
    }

    @Test
    fun `aes-128 key without iv is derived from the media sequence per spec`() {
        assertEquals("00000000000000000000000000000000", HlsKeyInfo.derivedIvHex(0L))
        assertEquals("00000000000000000000000000000005", HlsKeyInfo.derivedIvHex(5L))
        assertEquals("00000000000000001122334455667788", HlsKeyInfo.derivedIvHex(0x1122334455667788L))

        val text =
            """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI=http://cdn.test/key.bin
            #EXTINF:4.0,
            seg.ts
            #EXT-X-ENDLIST
            """.trimIndent()

        val media = parse(text, base) as HlsPlaylist.Media
        assertNull(media.encryption!!.ivHex) // downloader derives it from segment.sequence = 7
        assertEquals(7L, media.segments[0].sequence)
    }

    @Test
    fun `none method and unquoted key uri parse cleanly`() {
        val text =
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4.0,
            seg.ts
            #EXT-X-ENDLIST
            """.trimIndent()

        val media = parse(text, base) as HlsPlaylist.Media

        assertNull(media.encryption)
    }

    @Test
    fun `fmp4 map produces the init segment url`() {
        val text =
            """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg.ts
            #EXT-X-ENDLIST
            """.trimIndent()

        val media = parse(text, base) as HlsPlaylist.Media

        assertEquals("http://cdn.test/vod/init.mp4", media.initSegmentUrl)
    }

    @Test
    fun `missing endlist marks the playlist as live`() {
        val text =
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.0,
            seg.ts
            """.trimIndent()

        val media = parse(text, base) as HlsPlaylist.Media

        assertTrue(media.isLive)
    }

    @Test
    fun `crlf and bom are tolerated`() {
        val text = "\uFEFF#EXTM3U\r\n#EXT-X-ENDLIST\r\n#EXTINF:4.0,\r\nseg.ts\r\n"

        val media = parse(text, base) as HlsPlaylist.Media

        assertEquals(1, media.segments.size)
        assertEquals("http://cdn.test/vod/seg.ts", media.segments[0].url)
        assertFalse(media.isLive)
    }

    @Test
    fun `comments and unknown tags are ignored`() {
        val text =
            """
            #EXTM3U
            # a plain comment line
            #EXT-X-DISCONTINUITY
            #EXT-X-PROGRAM-DATE-TIME:2024-01-01T00:00:00Z
            #EXTINF:4.0,
            seg.ts
            #EXT-X-ENDLIST
            """.trimIndent()

        val media = parse(text, base) as HlsPlaylist.Media

        assertEquals(1, media.segments.size)
        assertEquals(4.0, media.segments[0].durationSec, 1e-9)
    }

    @Test
    fun `byterange tag is skipped and the whole segment is planned`() {
        val text =
            """
            #EXTM3U
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:1000@0
            seg.ts
            #EXT-X-ENDLIST
            """.trimIndent()

        val media = parse(text, base) as HlsPlaylist.Media

        assertEquals(1, media.segments.size)
        assertEquals("http://cdn.test/vod/seg.ts", media.segments[0].url)
    }

    @Test
    fun `garbage and blank input throw with a clear message`() {
        for (garbage in listOf("", "   \n ", "hello world", "<html><body>404</body></html>", "#EXTINF:4.0,\nseg.ts")) {
            try {
                parse(garbage, base)
                throw AssertionError("Expected IllegalArgumentException for: $garbage")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("HLS") || expected.message!!.contains("EXTM3U"))
            }
        }
    }
}
