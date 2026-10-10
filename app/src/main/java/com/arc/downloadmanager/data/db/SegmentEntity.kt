package com.arc.downloadmanager.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.arc.downloadmanager.domain.model.DownloadStatus

@Entity(tableName = "segments", primaryKeys = ["downloadId", "idx"])
data class SegmentEntity(
    val downloadId: String,
    @ColumnInfo(name = "idx") val index: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val length: Long
        get() = endByte - startByte + 1
}
