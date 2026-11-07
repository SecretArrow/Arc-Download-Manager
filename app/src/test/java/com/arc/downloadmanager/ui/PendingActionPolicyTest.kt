package com.arc.downloadmanager.ui

import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.ui.components.PendingAction
import com.arc.downloadmanager.ui.components.PendingActionPolicy
import com.arc.downloadmanager.ui.components.TaskActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-flight action feedback: spinner stays until the engine reflects the click (or timeout). */
class PendingActionPolicyTest {
    private val clickedAt = 1_000_000L

    private fun pending(
        action: String = TaskActions.RESUME,
        from: DownloadStatus = DownloadStatus.PAUSED,
        at: Long = clickedAt,
    ) = PendingAction(action, from, at)

    @Test
    fun `active while task still sits in the clicked-from status`() {
        assertTrue(
            PendingActionPolicy.isActive(
                pending(),
                DownloadStatus.PAUSED,
                nowMs = clickedAt + PendingActionPolicy.DEFAULT_TIMEOUT_MS - 1,
            ),
        )
    }

    @Test
    fun `resolves as soon as the status moves`() {
        assertFalse(
            PendingActionPolicy.isActive(
                pending(from = DownloadStatus.PAUSED),
                DownloadStatus.QUEUED,
                nowMs = clickedAt + 50,
            ),
        )
    }

    @Test
    fun `resolves at the timeout even if the status never moved`() {
        assertFalse(
            PendingActionPolicy.isActive(
                pending(),
                DownloadStatus.PAUSED,
                nowMs = clickedAt + PendingActionPolicy.DEFAULT_TIMEOUT_MS,
            ),
        )
    }

    @Test
    fun `pause pending clears when pausing transition starts`() {
        assertFalse(
            PendingActionPolicy.isActive(
                pending(action = TaskActions.PAUSE, from = DownloadStatus.DOWNLOADING),
                DownloadStatus.PAUSING,
                nowMs = clickedAt + 10,
            ),
        )
    }

    @Test
    fun `activeOnly keeps only entries whose task exists and has not moved`() {
        val map =
            mapOf(
                "a" to pending(at = clickedAt),
                "b" to pending(action = TaskActions.RETRY, from = DownloadStatus.FAILED, at = clickedAt - 10_000),
                "c" to pending(at = clickedAt),
            )
        val statuses =
            mapOf(
                "a" to DownloadStatus.PAUSED,
                // "b" vanished from the list (deleted) -> dropped
                "c" to DownloadStatus.QUEUED,
            )
        val live = PendingActionPolicy.activeOnly(map, statuses, nowMs = clickedAt + 100)
        assertEquals(setOf("a"), live.keys)
        assertEquals(TaskActions.RESUME, live["a"]!!.action)
    }

    @Test
    fun `custom timeout is honoured`() {
        assertTrue(PendingActionPolicy.isActive(pending(), DownloadStatus.PAUSED, nowMs = clickedAt + 400, timeoutMs = 500))
        assertFalse(PendingActionPolicy.isActive(pending(), DownloadStatus.PAUSED, nowMs = clickedAt + 600, timeoutMs = 500))
    }
}
