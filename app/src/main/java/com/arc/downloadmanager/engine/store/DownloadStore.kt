package com.arc.downloadmanager.engine.store

import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.db.SegmentEntity
import com.arc.downloadmanager.domain.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

/**
 * Persistence abstraction used by the download engine so the engine can run and be tested
 * on the JVM without an Android context.
 */
interface DownloadStore {
    fun observeAll(): Flow<List<DownloadEntity>>

    /** Non-archived tasks, newest first — the main UI list. */
    fun observeVisible(): Flow<List<DownloadEntity>>

    /** Archived tasks, most recently archived first. */
    fun observeArchived(): Flow<List<DownloadEntity>>

    fun observeById(id: String): Flow<DownloadEntity?>

    suspend fun getById(id: String): DownloadEntity?

    suspend fun getAllNow(): List<DownloadEntity>

    suspend fun getByUrl(url: String): List<DownloadEntity>

    suspend fun pending(): List<DownloadEntity>

    suspend fun running(): List<DownloadEntity>

    suspend fun interrupted(): List<DownloadEntity>

    fun observeActiveCount(): Flow<Int>

    suspend fun insert(entity: DownloadEntity)

    suspend fun update(entity: DownloadEntity)

    suspend fun updateProgress(
        id: String,
        downloadedBytes: Long,
        totalBytes: Long,
    )

    suspend fun updateStatus(
        id: String,
        status: DownloadStatus,
        errorCode: String? = null,
        errorMessage: String? = null,
    )

    suspend fun resetForRetry(id: String)

    suspend fun incrementRetry(id: String)

    suspend fun updateFinalUri(
        id: String,
        uri: String?,
    )

    suspend fun delete(id: String)

    /** Bulk delete regardless of status. */
    suspend fun deleteMany(ids: List<String>)

    /** Deletes visible COMPLETED and CANCELED tasks; returns how many rows were removed. */
    suspend fun clearFinished(): Int

    /** Archives (archivedAt = timestamp) or restores (archivedAt = null) tasks in bulk. */
    suspend fun setArchived(
        ids: List<String>,
        archivedAt: Long?,
    )

    /** Replaces the mutable task metadata (URL + display filename). */
    suspend fun updateMeta(
        id: String,
        url: String,
        filename: String,
    )

    suspend fun totalDownloadedBytes(): Long

    // Segments
    suspend fun segmentsFor(downloadId: String): List<SegmentEntity>

    fun observeSegments(downloadId: String): Flow<List<SegmentEntity>>

    suspend fun saveSegments(segments: List<SegmentEntity>)

    suspend fun updateSegmentProgress(
        downloadId: String,
        index: Int,
        downloadedBytes: Long,
        status: DownloadStatus,
    )

    suspend fun totalSegmentBytes(downloadId: String): Long

    suspend fun deleteSegments(downloadId: String)
}
