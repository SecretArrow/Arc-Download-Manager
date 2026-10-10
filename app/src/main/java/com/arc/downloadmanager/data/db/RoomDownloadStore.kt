package com.arc.downloadmanager.data.db

import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.store.DownloadStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDownloadStore @Inject constructor(
    private val db: ArcDatabase,
) : DownloadStore {
    private val downloadDao = db.downloadDao()
    private val segmentDao = db.segmentDao()

    override fun observeAll(): Flow<List<DownloadEntity>> = downloadDao.observeAll()

    override fun observeById(id: String): Flow<DownloadEntity?> = downloadDao.observeById(id)

    override suspend fun getById(id: String): DownloadEntity? = downloadDao.getById(id)

    override suspend fun getAllNow(): List<DownloadEntity> = downloadDao.getAllNow()

    override suspend fun getByUrl(url: String): List<DownloadEntity> = downloadDao.getByUrl(url)

    override suspend fun pending(): List<DownloadEntity> = downloadDao.pending()

    override suspend fun running(): List<DownloadEntity> = downloadDao.running()

    override suspend fun interrupted(): List<DownloadEntity> = downloadDao.interrupted()

    override fun observeActiveCount(): Flow<Int> = downloadDao.observeActiveCount()

    override suspend fun insert(entity: DownloadEntity) = downloadDao.upsert(entity)

    override suspend fun update(entity: DownloadEntity) = downloadDao.upsert(entity)

    override suspend fun updateProgress(id: String, downloadedBytes: Long, totalBytes: Long) =
        downloadDao.updateProgress(id, downloadedBytes, totalBytes)

    override suspend fun updateStatus(id: String, status: DownloadStatus, errorCode: String?, errorMessage: String?) =
        downloadDao.updateStatus(id, status.name, errorCode, errorMessage)

    override suspend fun resetForRetry(id: String) = downloadDao.resetForRetry(id, DownloadStatus.QUEUED.name)

    override suspend fun incrementRetry(id: String) = downloadDao.incrementRetry(id)

    override suspend fun updateFinalUri(id: String, uri: String?) = downloadDao.updateFinalUri(id, uri)

    override suspend fun delete(id: String) = downloadDao.delete(id)

    override suspend fun totalDownloadedBytes(): Long = downloadDao.totalDownloadedBytes()

    override suspend fun segmentsFor(downloadId: String): List<SegmentEntity> = segmentDao.getByDownload(downloadId)

    override fun observeSegments(downloadId: String): Flow<List<SegmentEntity>> = segmentDao.observeByDownload(downloadId)

    override suspend fun saveSegments(segments: List<SegmentEntity>) = segmentDao.upsertAll(segments)

    override suspend fun updateSegmentProgress(downloadId: String, index: Int, downloadedBytes: Long, status: DownloadStatus) =
        segmentDao.updateProgress(downloadId, index, downloadedBytes, status.name)

    override suspend fun totalSegmentBytes(downloadId: String): Long = segmentDao.totalDownloaded(downloadId)

    override suspend fun deleteSegments(downloadId: String) = segmentDao.deleteByDownload(downloadId)
}
