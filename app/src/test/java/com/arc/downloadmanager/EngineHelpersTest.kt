package com.arc.downloadmanager

import com.arc.downloadmanager.domain.model.ByteRange
import com.arc.downloadmanager.engine.BackoffPolicy
import com.arc.downloadmanager.engine.Checksum
import com.arc.downloadmanager.engine.ContentRangeParser
import com.arc.downloadmanager.engine.CookieScopePolicy
import com.arc.downloadmanager.engine.FilenameDetector
import com.arc.downloadmanager.engine.MergeManager
import com.arc.downloadmanager.engine.RangeSupportDetector
import com.arc.downloadmanager.engine.SegmentPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

class SegmentPlannerTest {
    @Test
    fun `small files use a single segment`() {
        val ranges = SegmentPlanner.plan(1024L, 8)
        assertEquals(1, ranges.size)
        assertEquals(ByteRange(0, 1023), ranges[0])
    }

    @Test
    fun `large files are split evenly and cover the whole file`() {
        val total = 9L * SegmentPlanner.AUTO_THRESHOLD
        val ranges = SegmentPlanner.plan(total, 4)
        assertEquals(4, ranges.size)
        assertTrue(SegmentPlanner.isCoverageComplete(ranges, total))
        assertEquals(total, ranges.sumOf { it.length })
    }

    @Test
    fun `ranges never overlap`() {
        val total = 50L * 1024L * 1024L
        val ranges = SegmentPlanner.plan(total, 8)
        val sorted = ranges.sortedBy { it.start }
        for (i in 1 until sorted.size) {
            assertTrue(sorted[i].start > sorted[i - 1].endInclusive)
        }
    }

    @Test
    fun `estimate respects threshold`() {
        assertEquals(1, SegmentPlanner.estimateSegments(1000L, 8))
        assertTrue(SegmentPlanner.estimateSegments(200L * 1024L * 1024L, 4) <= 4)
    }
}

class ContentRangeParserTest {
    @Test
    fun `parses valid header`() {
        assertEquals(100L to 999L, ContentRangeParser.parse("bytes 100-999/1234"))
        assertEquals(0L to 0L, ContentRangeParser.parse("bytes 0-0/12345"))
    }

    @Test
    fun `handles unknown total`() {
        assertEquals(10L to 20L, ContentRangeParser.parse("bytes 10-20/*"))
    }

    @Test
    fun `rejects malformed headers`() {
        assertNull(ContentRangeParser.parse(null))
        assertNull(ContentRangeParser.parse(""))
        assertNull(ContentRangeParser.parse("items 1-2/10"))
        assertNull(ContentRangeParser.parse("bytes 9-5/10"))
    }

    @Test
    fun `extracts total size`() {
        assertEquals(1234L, ContentRangeParser.totalSize("bytes 0-99/1234"))
        assertEquals(-1L, ContentRangeParser.totalSize("bytes 0-99/*"))
        assertEquals(-1L, ContentRangeParser.totalSize(null))
    }
}

class RangeSupportDetectorTest {
    @Test
    fun `206 implies support`() {
        assertTrue(RangeSupportDetector.supports(emptyMap(), 206))
    }

    @Test
    fun `accept-ranges bytes implies support`() {
        assertTrue(RangeSupportDetector.supports(mapOf("Accept-Ranges" to listOf("bytes")), 200))
        assertFalse(RangeSupportDetector.supports(mapOf("Accept-Ranges" to listOf("none")), 200))
    }
}

class FilenameDetectorTest {
    @Test
    fun `extracts from content disposition`() {
        assertEquals("report.pdf", FilenameDetector.fromDisposition("attachment; filename=\"report.pdf\""))
        assertEquals("résumé.pdf", FilenameDetector.fromDisposition("attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf"))
    }

    @Test
    fun `extracts from url`() {
        assertEquals("archive.zip", FilenameDetector.fromUrl("https://example.com/files/archive.zip?token=abc"))
        assertEquals("movie", FilenameDetector.fromUrl("https://example.com/watch/movie"))
    }

    @Test
    fun `detects and appends extension by mime`() {
        val name = FilenameDetector.detect("https://example.com/download?id=1", null, "application/pdf", 42L)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test
    fun `sanitizes unsafe characters`() {
        assertEquals("file_1_.txt", FilenameDetector.sanitize("file:1?.txt"))
        assertTrue(FilenameDetector.sanitize("   ").isNotEmpty())
    }

    @Test
    fun `unique name avoids conflicts`() {
        val existing = setOf("a.txt", "a (1).txt")
        assertEquals("a (2).txt", FilenameDetector.uniqueName("a.txt") { it in existing })
    }
}

class BackoffPolicyTest {
    @Test
    fun `grows exponentially and is capped`() {
        val backoff = BackoffPolicy(baseDelayMs = 1000, maxDelayMs = 60_000, random = Random(1))
        val d1 = backoff.delayFor(1)
        val d5 = backoff.delayFor(5)
        val d20 = backoff.delayFor(20)
        assertTrue(d1 in 1000..1300)
        assertTrue(d5 >= 1000 * 16)
        assertTrue(d20 >= 60_000L)
    }

    @Test
    fun `honors retry-after`() {
        val backoff = BackoffPolicy(baseDelayMs = 1000, maxDelayMs = 60_000)
        assertEquals(30_000L, backoff.delayFor(1, retryAfterSeconds = 30))
        assertEquals(60_000L, backoff.delayFor(1, retryAfterSeconds = 300))
    }
}

class SpeedTrackerTest {
    @Test
    fun `computes smoothed speed`() {
        val tracker = com.arc.downloadmanager.util.SpeedTracker()
        val s1 = tracker.record("a", 0, nowMs = 0)
        val s2 = tracker.record("a", 3_000, nowMs = 1_000)
        assertEquals(3_000L, s2)
    }

    @Test
    fun `handles reset progress`() {
        val tracker = com.arc.downloadmanager.util.SpeedTracker()
        tracker.record("a", 1_000, nowMs = 0)
        val speed = tracker.record("a", 0, nowMs = 1_000)
        assertEquals(0L, speed)
    }
}

class CookieScopePolicyTest {
    @Test
    fun `same host cookies allowed`() {
        val decision = CookieScopePolicy.evaluate("https://a.example.com/page", "https://a.example.com/file.zip")
        assertTrue(decision.allowCookies)
    }

    @Test
    fun `cross host cookies rejected`() {
        val decision = CookieScopePolicy.evaluate("https://a.example.com/", "https://evil.example.net/file")
        assertFalse(decision.allowCookies)
    }

    @Test
    fun `https to http rejected`() {
        val decision = CookieScopePolicy.evaluate("https://a.example.com/", "http://a.example.com/file")
        assertFalse(decision.allowCookies)
    }

    @Test
    fun `parses cookie header`() {
        val cookies = CookieScopePolicy.parseCookies("session=abc; theme=dark; broken")
        assertEquals("abc", cookies["session"])
        assertEquals("dark", cookies["theme"])
        assertEquals(2, cookies.size)
    }

    @Test
    fun `validates supported urls`() {
        assertTrue(CookieScopePolicy.isSupportedUrl("https://example.com/x"))
        assertTrue(CookieScopePolicy.isSupportedUrl("http://example.com/x"))
        // Multi-protocol engine: ftp/ftps/sftp/content are now first-class schemes.
        assertTrue(CookieScopePolicy.isSupportedUrl("ftp://example.com/file.zip"))
        assertTrue(CookieScopePolicy.isSupportedUrl("sftp://example.com/file.zip"))
        assertTrue(CookieScopePolicy.isSupportedUrl("content://downloads/all/1"))
        assertTrue(CookieScopePolicy.isSupportedUrl("magnet:?xt=urn:btih:abc"))
        assertFalse(CookieScopePolicy.isSupportedUrl("not a url"))
    }

    @Test
    fun `redacts query strings`() {
        assertEquals("https://example.com/file?…", CookieScopePolicy.redact("https://example.com/file?token=secret"))
    }
}

class ChecksumTest {
    @Test
    fun `sha256 matches known vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Checksum.normalize(sha256Of("")),
        )
    }

    @Test
    fun `normalizes prefixed input`() {
        assertEquals(
            "abc",
            Checksum.normalize("SHA256: ABC"),
        )
    }

    private fun sha256Of(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

class MergeManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `merges parts in order`() {
        val part1 = tmp.newFile("p0").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val part2 = tmp.newFile("p1").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val output = File(tmp.root, "out.bin")
        MergeManager.merge(listOf(part1, part2), output)
        assertTrue(output.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4, 5, 6)))
    }

    @Test(expected = java.io.IOException::class)
    fun `fails on missing part`() {
        val output = File(tmp.root, "out2.bin")
        MergeManager.merge(listOf(File(tmp.root, "missing")), output)
    }
}
