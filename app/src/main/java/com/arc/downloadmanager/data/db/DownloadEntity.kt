package com.arc.downloadmanager.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.domain.model.Priority

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val url: String,
    val finalUrl: String? = null,
    val filename: String,
    val destinationUri: String? = null,
    val finalUri: String? = null,
    val mimeType: String? = null,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val priority: Priority = Priority.NORMAL,
    val segmentsPlanned: Int = 0,
    val segmentsAuto: Boolean = true,
    val acceptRanges: Boolean = false,
    val eTag: String? = null,
    val lastModified: String? = null,
    val resumable: Boolean = false,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookies: String? = null,
    val requiresSession: Boolean = false,
    val checksumSha256: String? = null,
    val expectedSha256: String? = null,
    val wifiOnly: Boolean = false,
    val retryCount: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val source: String = "MANUAL",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    /** Non-null when the task is archived (hidden from the main queue, never auto-resumed). */
    val archivedAt: Long? = null,
)
