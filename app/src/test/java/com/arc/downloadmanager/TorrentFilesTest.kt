package com.arc.downloadmanager

import com.arc.downloadmanager.engine.torrent.TorrentFiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TorrentFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `collect returns payload files recursively`() {
        val root = tmp.newFolder("task")
        File(root, "movie.mkv").writeBytes(byteArrayOf(1))
        File(File(root, "subtitles")).apply { mkdirs() }
        File(File(root, "subtitles"), "movie.srt").writeBytes(byteArrayOf(2))
        assertEquals(listOf("movie.mkv", "movie.srt"), TorrentFiles.collect(root).map { it.name })
    }

    @Test
    fun `collect skips hidden bookkeeping and pad files`() {
        val root = tmp.newFolder("task")
        File(root, "data.bin").writeBytes(byteArrayOf(1))
        File(root, ".parts").writeBytes(byteArrayOf(2))
        File(File(root, ".pad")).apply { mkdirs() }
        File(File(root, ".pad"), "1048576").writeBytes(ByteArray(64))
        assertEquals(listOf("data.bin"), TorrentFiles.collect(root).map { it.name })
    }

    @Test
    fun `collect on missing dir returns empty`() {
        assertEquals(emptyList<File>(), TorrentFiles.collect(File(tmp.root, "missing")))
    }

    @Test
    fun `totalBytes sums payload`() {
        val root = tmp.newFolder("task")
        File(root, "a").writeBytes(ByteArray(10))
        File(root, "b").writeBytes(ByteArray(15))
        assertEquals(25L, TorrentFiles.totalBytes(TorrentFiles.collect(root)))
    }

    @Test
    fun `guessMime maps common extensions`() {
        assertEquals("video/x-matroska", TorrentFiles.guessMime("Movie.mkv"))
        assertEquals("video/mp4", TorrentFiles.guessMime("clip.MP4"))
        assertEquals("application/pdf", TorrentFiles.guessMime("doc.pdf"))
        assertEquals("application/x-iso9660-image", TorrentFiles.guessMime("ubuntu.iso"))
        assertNull(TorrentFiles.guessMime("mystery.xyz"))
        assertNull(TorrentFiles.guessMime("noextension"))
    }
}
