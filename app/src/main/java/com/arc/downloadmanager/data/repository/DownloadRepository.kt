package com.arc.downloadmanager.data.repository

import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.settings.EngineSettings
import com.arc.downloadmanager.data.settings.OverwritePolicy
import com.arc.downloadmanager.data.settings.SettingsRepository
import com.arc.downloadmanager.domain.model.DownloadRequest
import com.arc.downloadmanager.domain.model.DownloadSource
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.DownloadEngine
import com.arc.downloadmanager.engine.FilenameDetector
import com.arc.downloadmanager.engine.StorageManager
import com.arc.downloadmanager.engine.store.DownloadStore
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AddResult {
    data class Started(val id: String) : AddResult

    data class Duplicate(val existingId: String) : AddResult

    data class Invalid(val reason: String) : AddResult
}

data class BatchResult(val started: Int, val invalid: Int)

/**
 * Use-case layer between UI and the engine: filename detection, duplicate handling and
 * entity construction live here.
 */
@Singleton
class DownloadRepository
    @Inject
    constructor(
        private val engine: DownloadEngine,
        private val store: DownloadStore,
        private val settingsRepository: SettingsRepository,
        private val storageManager: StorageManager,
    ) {
        suspend fun add(
            request: DownloadRequest,
            policyOverride: OverwritePolicy? = null,
        ): AddResult {
            val url = request.url.trim()
            if (url.isEmpty()) return AddResult.Invalid("empty")
            val settings = settingsRepository.snapshot()
            val policy = policyOverride ?: settings.overwritePolicy

            val existing = store.getByUrl(url).filter { !it.status.isTerminal || it.status == DownloadStatus.COMPLETED }
            if (existing.isNotEmpty() && policy == OverwritePolicy.SKIP && request.source != DownloadSource.BROWSER) {
                return AddResult.Duplicate(existing.first().id)
            }
            if (existing.isNotEmpty() && policy != OverwritePolicy.SKIP && request.source == DownloadSource.MANUAL) {
                // Let the UI decide when the policy is RENAME (ask) — REPLACE proceeds below.
                if (policy == OverwritePolicy.RENAME && policyOverride == null) {
                    return AddResult.Duplicate(existing.first().id)
                }
            }
            if (policy == OverwritePolicy.REPLACE && existing.isNotEmpty()) {
                existing.forEach { old ->
                    if (old.status == DownloadStatus.COMPLETED) storageManager.deleteFinal(old.finalUri)
                    engine.cancel(old.id)
                    store.delete(old.id)
                }
            }

            val id = java.util.UUID.randomUUID().toString()
            val filename =
                request.filename
                    ?: magnetDisplayName(url)
                    ?: FilenameDetector.detect(url, request.contentDisposition, request.mimeType, fallbackSeed = System.currentTimeMillis())
            val entity =
                DownloadEntity(
                    id = id,
                    url = url,
                    filename = filename,
                    destinationUri =
                        (request.destination as? com.arc.downloadmanager.domain.model.Destination.CustomFolder)?.treeUri
                            ?: settings.defaultDestinationUri,
                    priority = request.priority,
                    segmentsPlanned = request.segments,
                    segmentsAuto = request.segments <= 0,
                    wifiOnly = request.wifiOnly ?: settings.wifiOnly,
                    referer = request.referer,
                    userAgent = request.userAgent,
                    cookies = request.cookies,
                    requiresSession = !request.cookies.isNullOrBlank() || request.source == DownloadSource.BROWSER,
                    expectedSha256 = request.checksum?.let { com.arc.downloadmanager.engine.Checksum.normalize(it) },
                    source = request.source.name,
                )
            engine.enqueue(entity)
            return AddResult.Started(id)
        }

        suspend fun addBatch(
            requests: List<DownloadRequest>,
            policyOverride: OverwritePolicy? = null,
        ): BatchResult {
            var started = 0
            var invalid = 0
            requests.forEach { request ->
                when (add(request, policyOverride)) {
                    is AddResult.Started -> started++
                    else -> invalid++
                }
            }
            return BatchResult(started, invalid)
        }

        fun observeAll() = store.observeAll()

        fun observeVisible() = store.observeVisible()

        fun observeArchived() = store.observeArchived()

        fun observeById(id: String) = store.observeById(id)

        fun observeSegments(id: String) = store.observeSegments(id)

        fun observeActiveCount() = store.observeActiveCount()

        fun observeRecentCompleted(limit: Int = 6): kotlinx.coroutines.flow.Flow<List<DownloadEntity>> =
            kotlinx.coroutines.flow.flow {
                store.observeAll().collect { all ->
                    emit(
                        all.filter { it.status == DownloadStatus.COMPLETED }
                            .sortedByDescending { it.completedAt ?: 0 }
                            .take(limit),
                    )
                }
            }

        suspend fun get(id: String): DownloadEntity? = store.getById(id)

        suspend fun stats(): com.arc.downloadmanager.domain.model.QueueStats {
            val all = store.getAllNow()
            return com.arc.downloadmanager.domain.model.QueueStats(
                active = all.count { it.status.isActive },
                queued = all.count { it.status.isRunningCandidate },
                failed = all.count { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.AUTH_REQUIRED },
                completed = all.count { it.status == DownloadStatus.COMPLETED },
                totalBytesDownloaded = all.filter { it.status.isActive }.sumOf { it.downloadedBytes },
                totalBytesAll = all.filter { it.status.isActive }.sumOf { it.totalBytes.coerceAtLeast(0) },
            )
        }

        // Engine pass-through actions
        fun pause(id: String) = engine.pause(id)

        fun resume(id: String) = engine.resume(id)

        fun cancel(id: String) = engine.cancel(id)

        fun retry(id: String) = engine.retry(id)

        fun restart(id: String) = engine.restart(id)

        fun pauseAll() = engine.pauseAll()

        fun resumeAll() = engine.resumeAll()

        suspend fun delete(
            id: String,
            deleteFile: Boolean,
        ) = engine.delete(id, deleteFile)

        suspend fun deleteMany(
            ids: List<String>,
            deleteFiles: Boolean,
        ) = engine.deleteMany(ids, deleteFiles)

        suspend fun clearFinished(): Int = engine.clearFinished()

        fun archive(
            ids: List<String>,
            archived: Boolean,
        ) = engine.archive(ids, archived)

        suspend fun setTaskMeta(
            id: String,
            url: String,
            filename: String,
        ): Boolean = engine.setTaskMeta(id, url, filename)

        suspend fun defaultSettings(): EngineSettings = settingsRepository.snapshot()

        /**
         * Magnet links carry their display name in dn=. Use it as the task filename; without a
         * dn=, fall back to the engine placeholder ("magnet-<hash12>") so the runner swaps in
         * the real torrent name as soon as the metadata arrives.
         */
        private fun magnetDisplayName(url: String): String? {
            if (com.arc.downloadmanager.engine.Protocols.schemeOf(url) != com.arc.downloadmanager.engine.Protocols.MAGNET) return null
            val dn = com.arc.downloadmanager.engine.MagnetUri.displayName(url)
            if (!dn.isNullOrBlank()) {
                return FilenameDetector.sanitize(dn).takeIf { it.isNotBlank() }
            }
            val hash = com.arc.downloadmanager.engine.MagnetUri.infoHashHex(url)
            return if (hash != null) "magnet-${hash.take(12)}" else "magnet-download"
        }
    }
