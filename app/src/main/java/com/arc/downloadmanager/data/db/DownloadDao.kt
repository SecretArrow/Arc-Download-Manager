package com.arc.downloadmanager.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE archivedAt IS NULL ORDER BY createdAt DESC")
    fun observeVisible(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE archivedAt IS NOT NULL ORDER BY archivedAt DESC")
    fun observeArchived(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observeById(id: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads")
    suspend fun getAllNow(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE url = :url AND status != 'CANCELED' ORDER BY createdAt DESC")
    suspend fun getByUrl(url: String): List<DownloadEntity>

    @Query(
        "SELECT * FROM downloads WHERE status IN ('QUEUED','WAITING_NETWORK','WAITING_STORAGE','WAITING_SCHEDULED') AND archivedAt IS NULL ORDER BY priority DESC, createdAt ASC",
    )
    suspend fun pending(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status IN ('CONNECTING','DOWNLOADING','PAUSING','MERGING','VERIFYING') AND archivedAt IS NULL")
    suspend fun interrupted(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status IN ('CONNECTING','DOWNLOADING','PAUSING','MERGING','VERIFYING') AND archivedAt IS NULL")
    fun observeInterrupted(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('CONNECTING','DOWNLOADING','MERGING','VERIFYING') AND archivedAt IS NULL")
    suspend fun running(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('CONNECTING','DOWNLOADING','PAUSING','MERGING','VERIFYING')")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedAt DESC LIMIT :limit")
    fun observeRecentCompleted(limit: Int): Flow<List<DownloadEntity>>

    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Query("UPDATE downloads SET downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(
        id: String,
        downloadedBytes: Long,
        totalBytes: Long,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE downloads SET status = :status, errorCode = :errorCode, errorMessage = :errorMessage, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: String,
        errorCode: String?,
        errorMessage: String?,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE downloads SET status = :status, errorCode = NULL, errorMessage = NULL, retryCount = 0, updatedAt = :now WHERE id = :id")
    suspend fun resetForRetry(
        id: String,
        status: String,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE downloads SET retryCount = retryCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun incrementRetry(
        id: String,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE downloads SET finalUri = :uri, updatedAt = :now WHERE id = :id")
    suspend fun updateFinalUri(
        id: String,
        uri: String?,
        now: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM downloads WHERE id IN (:ids)")
    suspend fun deleteMany(ids: List<String>)

    @Query("DELETE FROM downloads WHERE status IN ('COMPLETED','CANCELED') AND archivedAt IS NULL")
    suspend fun clearFinished(): Int

    @Query("UPDATE downloads SET archivedAt = :archivedAt, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setArchived(
        ids: List<String>,
        archivedAt: Long?,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE downloads SET url = :url, filename = :filename, updatedAt = :now WHERE id = :id")
    suspend fun updateMeta(
        id: String,
        url: String,
        filename: String,
        now: Long = System.currentTimeMillis(),
    )

    @Query("SELECT COALESCE(SUM(downloadedBytes),0) FROM downloads")
    suspend fun totalDownloadedBytes(): Long
}
