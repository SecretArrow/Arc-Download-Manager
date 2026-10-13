package com.arc.downloadmanager

import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.TaskEditPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure task-edit rules (edit task feature). */
class TaskEditPolicyTest {
    private val url = "https://example.test/file.zip"

    @Test
    fun `same URL and filename is allowed without resetting progress`() {
        val verdict = TaskEditPolicy.evaluate(DownloadStatus.PAUSED, url, url, "file.zip")
        assertTrue(verdict is TaskEditPolicy.Verdict.Allowed)
        assertFalse((verdict as TaskEditPolicy.Verdict.Allowed).resetProgress)
    }

    @Test
    fun `URL change is allowed but resets progress`() {
        val verdict = TaskEditPolicy.evaluate(DownloadStatus.PAUSED, url, "https://example.test/other.iso", "file.zip")
        assertTrue(verdict is TaskEditPolicy.Verdict.Allowed)
        assertTrue((verdict as TaskEditPolicy.Verdict.Allowed).resetProgress)
    }

    @Test
    fun `active tasks are rejected`() {
        DownloadStatus.entries
            .filter { it.isActive }
            .forEach { status ->
                val verdict = TaskEditPolicy.evaluate(status, url, url, "file.zip")
                assertEquals(TaskEditPolicy.Verdict.Rejected(TaskEditPolicy.Reason.ACTIVE), verdict)
            }
    }

    @Test
    fun `completed tasks are rejected`() {
        val verdict = TaskEditPolicy.evaluate(DownloadStatus.COMPLETED, url, url, "file.zip")
        assertEquals(TaskEditPolicy.Verdict.Rejected(TaskEditPolicy.Reason.COMPLETED), verdict)
    }

    @Test
    fun `unsupported scheme is rejected`() {
        val verdict = TaskEditPolicy.evaluate(DownloadStatus.PAUSED, url, "rtsp://stream.example.com/live", "file.zip")
        assertEquals(TaskEditPolicy.Verdict.Rejected(TaskEditPolicy.Reason.INVALID_URL), verdict)
    }

    @Test
    fun `magnet url is now a valid edit target`() {
        // magnet became a supported scheme in v1.7.0 (BitTorrent engine).
        val verdict = TaskEditPolicy.evaluate(DownloadStatus.PAUSED, url, "magnet:?xt=urn:btih:xyz", "file.zip")
        assertEquals(TaskEditPolicy.Verdict.Allowed(resetProgress = true), verdict)
    }

    @Test
    fun `blank url or filename is rejected`() {
        assertEquals(
            TaskEditPolicy.Verdict.Rejected(TaskEditPolicy.Reason.INVALID_URL),
            TaskEditPolicy.evaluate(DownloadStatus.PAUSED, url, "   ", "file.zip"),
        )
        assertEquals(
            TaskEditPolicy.Verdict.Rejected(TaskEditPolicy.Reason.INVALID_FILENAME),
            TaskEditPolicy.evaluate(DownloadStatus.PAUSED, url, url, "  "),
        )
    }

    @Test
    fun `all supported protocols pass URL validation`() {
        listOf(
            "https://example.test/f.zip",
            "http://example.test/f.zip",
            "ftp://ftp.example.test/f.zip",
            "ftps://ftp.example.test/f.zip",
            "sftp://sftp.example.test/f.zip",
            "content://media/external/downloads/42",
        ).forEach { supported ->
            val verdict = TaskEditPolicy.evaluate(DownloadStatus.PAUSED, url, supported, "f.zip")
            assertTrue("expected Allowed for $supported", verdict is TaskEditPolicy.Verdict.Allowed)
        }
    }

    @Test
    fun `terminal-but-resumable statuses allow editing`() {
        listOf(DownloadStatus.FAILED, DownloadStatus.CANCELED, DownloadStatus.AUTH_REQUIRED, DownloadStatus.QUEUED).forEach { status ->
            val verdict = TaskEditPolicy.evaluate(status, url, url, "renamed.zip")
            assertTrue("expected Allowed for $status", verdict is TaskEditPolicy.Verdict.Allowed)
        }
    }
}
