package com.arc.downloadmanager.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SegmentDao {
    @Query("SELECT * FROM segments WHERE downloadId = :downloadId ORDER BY index ASC")
    suspend fun getByDownload(downloadId: String): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE downloadId = :downloadId ORDER BY index ASC")
    fun observeByDownload(downloadId: String): Flow<List<SegmentEntity>>

    @Query("SELECT COALESCE(SUM(downloadedBytes),0) FROM segments WHERE downloadId = :downloadId")
    suspend fun totalDownloaded(downloadId: String): Long

    @Upsert
    suspend fun upsert(segment: SegmentEntity)

    @Upsert
    suspend fun upsertAll(segments: List<SegmentEntity>)

    @Query("UPDATE segments SET downloadedBytes = :downloadedBytes, status = :status, updatedAt = :now WHERE downloadId = :downloadId AND index = :index")
    suspend fun updateProgress(downloadId: String, index: Int, downloadedBytes: Long, status: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM segments WHERE downloadId = :downloadId")
    suspend fun deleteByDownload(downloadId: String)
}
