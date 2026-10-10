package com.arc.downloadmanager

import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.db.SegmentEntity
import com.arc.downloadmanager.data.settings.EngineSettings
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.ConnectivityView
import com.arc.downloadmanager.engine.CookieProvider
import com.arc.downloadmanager.engine.EngineSettingsSource
import com.arc.downloadmanager.engine.TaskStorage
import com.arc.downloadmanager.engine.store.DownloadStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Thread-safe in-memory [DownloadStore] for JVM tests. */
class InMemoryDownloadStore : DownloadStore {
    private val mutex = Any()
    private val downloads = linkedMapOf<String, DownloadEntity>()
    private val segments = linkedMapOf<String, LinkedHashMap<Int, SegmentEntity>>()

    override fun observeAll(): Flow<List<DownloadEntity>> =
        MutableStateFlow(0).map { synchronized(mutex) { downloads.values.toList() } }

    override fun observeById(id: String): Flow<DownloadEntity?> =
        MutableStateFlow(0).map { synchronized(mutex) { downloads[id] } }

    override suspend fun getById(id: String): DownloadEntity? = synchronized(mutex) { downloads[id] }

    override suspend fun getAllNow(): List<DownloadEntity> = synchronized(mutex) { downloads.values.toList() }

    override suspend fun getByUrl(url: String): List<DownloadEntity> =
        synchronized(mutex) { downloads.values.filter { it.url == url } }

    override suspend fun pending(): List<DownloadEntity> =
        synchronized(mutex) { downloads.values.filter { it.status.isRunningCandidate } }

    override suspend fun running(): List<DownloadEntity> =
        synchronized(mutex) { downloads.values.filter { it.status.isActive } }

    override suspend fun interrupted(): List<DownloadEntity> =
        synchronized(mutex) { downloads.values.filter { it.status.isActive } }

    override fun observeActiveCount(): Flow<Int> =
        MutableStateFlow(0).map { synchronized(mutex) { downloads.values.count { it.status.isActive } } }

    override suspend fun insert(entity: DownloadEntity) = synchronized(mutex) { downloads[entity.id] = entity }

    override suspend fun update(entity: DownloadEntity) = synchronized(mutex) { downloads[entity.id] = entity }

    override suspend fun updateProgress(id: String, downloadedBytes: Long, totalBytes: Long) = synchronized(mutex) {
        downloads[id]?.let { downloads[id] = it.copy(downloadedBytes = downloadedBytes, totalBytes = totalBytes) }
    }

    override suspend fun updateStatus(
        id: String,
        status: DownloadStatus,
        errorCode: String?,
        errorMessage: String?,
    ) = synchronized(mutex) {
        downloads[id]?.let {
            downloads[id] = it.copy(
                status = status,
                errorCode = errorCode,
                errorMessage = errorMessage,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun resetForRetry(id: String) = synchronized(mutex) {
        downloads[id]?.let {
            downloads[id] = it.copy(
                status = DownloadStatus.QUEUED,
                errorCode = null,
                errorMessage = null,
                retryCount = 0,
            )
        }
    }

    override suspend fun incrementRetry(id: String) = synchronized(mutex) {
        downloads[id]?.let { downloads[id] = it.copy(retryCount = it.retryCount + 1) }
    }

    override suspend fun updateFinalUri(id: String, uri: String?) = synchronized(mutex) {
        downloads[id]?.let { downloads[id] = it.copy(finalUri = uri) }
    }

    override suspend fun delete(id: String) = synchronized(mutex) {
        downloads.remove(id)
        segments.remove(id)
    }

    override suspend fun totalDownloadedBytes(): Long = synchronized(mutex) { downloads.values.sumOf { it.downloadedBytes } }

    override suspend fun segmentsFor(downloadId: String): List<SegmentEntity> =
        synchronized(mutex) { segments[downloadId]?.values?.sortedBy { it.index } ?: emptyList() }

    override fun observeSegments(downloadId: String): Flow<List<SegmentEntity>> =
        MutableStateFlow(0).map { synchronized(mutex) { segments[downloadId]?.values?.sortedBy { it.index } ?: emptyList() } }

    override suspend fun saveSegments(list: List<SegmentEntity>) = synchronized(mutex) {
        val map = segments.getOrPut(list.firstOrNull()?.downloadId ?: return) { LinkedHashMap() }
        list.forEach { map[it.index] = it }
    }

    override suspend fun updateSegmentProgress(
        downloadId: String,
        index: Int,
        downloadedBytes: Long,
        status: DownloadStatus,
    ) = synchronized(mutex) {
        segments[downloadId]?.get(index)?.let {
            segments[downloadId]!![index] = it.copy(downloadedBytes = downloadedBytes, status = status)
        }
    }

    override suspend fun totalSegmentBytes(downloadId: String): Long =
        synchronized(mutex) { segments[downloadId]?.values?.sumOf { it.downloadedBytes } ?: 0L }

    override suspend fun deleteSegments(downloadId: String) = synchronized(mutex) { segments.remove(downloadId) }
}

/** Temp-dir based [TaskStorage] for JVM tests. */
class FakeTaskStorage(private val root: File) : TaskStorage {

    override fun partFile(downloadId: String, index: Int): File =
        File(File(root, downloadId).apply { mkdirs() }, "part_$index")

    override fun stagingFile(downloadId: String, filename: String): File =
        File(root, "${downloadId}_$filename")

    override fun reconcilePart(downloadId: String, index: Int, recordedBytes: Long): Long {
        val file = partFile(downloadId, index)
        if (!file.exists()) return 0L
        if (file.length() > recordedBytes) {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(recordedBytes) }
        }
        return file.length()
    }

    override fun cleanup(downloadId: String) {
        File(root, downloadId).deleteRecursively()
    }

    override fun cleanupOrphans(validIds: Set<String>): Int = 0

    override suspend fun hasFreeSpace(bytesNeeded: Long): Boolean = true

    override suspend fun finalize(entity: DownloadEntity, staged: File): String {
        val dest = File(root, "final_${entity.filename}")
        staged.copyTo(dest, overwrite = true)
        staged.delete()
        return dest.toURI().toString()
    }

    override suspend fun deleteFinal(finalUri: String?): Boolean {
        if (finalUri == null) return false
        val path = runCatching { java.net.URI(finalUri).path }.getOrNull() ?: return false
        return File(path).delete()
    }
}

class FakeConnectivity(initialOnline: Boolean = true) : ConnectivityView {
    override val isOnline: MutableStateFlow<Boolean> = MutableStateFlow(initialOnline)
    override val isOnWifi: MutableStateFlow<Boolean> = MutableStateFlow(true)
}

class FixedSettingsSource(var current: EngineSettings = EngineSettings()) : EngineSettingsSource {
    override suspend fun snapshot(): EngineSettings = current

    override val settings: Flow<EngineSettings> = MutableStateFlow(EngineSettings()).map { current }
}

class FixedCookieProvider(private val cookies: Map<String, String> = emptyMap()) : CookieProvider {
    override fun cookiesFor(url: String): String? =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifBlank { null }
}
