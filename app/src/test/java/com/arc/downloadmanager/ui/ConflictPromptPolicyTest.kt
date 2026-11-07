package com.arc.downloadmanager.ui

import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.ui.components.ConflictPromptPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Selection of the next download whose filename conflict needs the user's decision. */
class ConflictPromptPolicyTest {
    @Test
    fun `picks the first unresolved conflict`() {
        val statuses =
            mapOf(
                "a" to DownloadStatus.COMPLETED,
                "b" to DownloadStatus.CONFLICT,
                "c" to DownloadStatus.CONFLICT,
            )
        assertEquals("b", ConflictPromptPolicy.next(statuses, emptySet()))
    }

    @Test
    fun `skips resolved ids and continues down the queue`() {
        val statuses =
            mapOf(
                "a" to DownloadStatus.CONFLICT,
                "b" to DownloadStatus.CONFLICT,
            )
        assertEquals("b", ConflictPromptPolicy.next(statuses, resolved = setOf("a")))
    }

    @Test
    fun `fully resolved queue yields null`() {
        val statuses = mapOf("a" to DownloadStatus.CONFLICT)
        assertNull(ConflictPromptPolicy.next(statuses, resolved = setOf("a")))
    }

    @Test
    fun `non conflict statuses never prompt`() {
        val statuses =
            mapOf(
                "a" to DownloadStatus.FAILED,
                "b" to DownloadStatus.DOWNLOADING,
                "c" to DownloadStatus.PAUSED,
            )
        assertNull(ConflictPromptPolicy.next(statuses, emptySet()))
    }
}
