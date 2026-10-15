package com.arc.downloadmanager.engine

import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.db.SegmentEntity
import com.arc.downloadmanager.data.settings.EngineSettings
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.hls.HlsDownloader
import com.arc.downloadmanager.engine.hls.HlsResult
import com.arc.downloadmanager.engine.hls.HlsSniffer
import com.arc.downloadmanager.engine.store.DownloadStore
import com.arc.downloadmanager.engine.torrent.TorrentEngine
import com.arc.downloadmanager.engine.torrent.TorrentFiles
import com.arc.downloadmanager.engine.torrent.TorrentRunState
import com.arc.downloadmanager.engine.transport.ContentStreams
import com.arc.downloadmanager.engine.transport.FtpAuthException
import com.arc.downloadmanager.engine.transport.FtpTransport
import com.arc.downloadmanager.engine.transport.SftpAuthException
import com.arc.downloadmanager.engine.transport.SftpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Protocol-agnostic stream source for the shared transfer loop. */
private class TransferSource(
    val resumableUsed: Boolean,
    val stream: () -> java.io.InputStream,
    val close: () -> Unit,
)

/** In-memory control channel used to pause or cancel a running task promptly. */
class TaskControl {
    @Volatile
    var pauseRequested: Boolean = false

    @Volatile
    var cancelRequested: Boolean = false
}

/** Terminal outcome of a task execution attempt. */
sealed interface TaskOutcome {
    data object Completed : TaskOutcome

    data class Paused(val reason: PauseReason) : TaskOutcome

    data object Canceled : TaskOutcome

    data class Failed(val error: DownloadError, val message: String?, val retryAfterSec: Long? = null) : TaskOutcome
}

enum class PauseReason { USER, NO_NETWORK, LOW_STORAGE, QUOTA }

/** Result of opening the initial HTTP stream for a task. */
private sealed interface StreamOpen {
    /** The probe identified an HLS playlist; the caller must run the HLS pipeline. */
    data object Hls : StreamOpen

    data class Opened(
        val response: okhttp3.Response,
        val finalUrl: String,
        val contentLength: Long,
        val contentRangeTotal: Long,
        val resumableUsed: Boolean,
        val restartFromZero: Boolean,
        val serverAcceptsRanges: Boolean,
        val eTag: String?,
        val lastModified: String?,
        val mimeType: String?,
        val dispositionFilename: String?,
    ) : StreamOpen

    data class Failure(val error: DownloadError, val message: String?, val retryAfterSec: Long? = null) : StreamOpen

    data object AuthRequired : StreamOpen
}

/**
 * Executes a single download task end-to-end: probe, range negotiation, single- or multi-stream
 * transfer, merge, verification and finalization. Pure engine logic on top of OkHttp + DownloadStore.
 */
@Singleton
class TaskRunner
    @Inject
    constructor(
        private val store: DownloadStore,
        private val baseClient: OkHttpClient,
        private val storage: TaskStorage,
        private val networkMonitor: ConnectivityView,
        private val cookieProvider: CookieProvider,
        private val speedLimitHolder: SpeedLimitHolder,
        private val contentStreams: ContentStreams? = null,
        private val torrentEngine: TorrentEngine? = null,
    ) {
        private val backoff = BackoffPolicy()
        private val ftpTransport = FtpTransport()
        private val sftpTransport = SftpTransport()

        suspend fun run(
            id: String,
            settings: EngineSettings,
            control: TaskControl,
            segmentCapFor: (String) -> Int,
            onEnded: suspend (String) -> Unit,
        ) {
            val outcome = execute(id, settings, control, segmentCapFor)
            finalizeOutcome(id, outcome, settings, control)
            onEnded(id)
        }

        private suspend fun execute(
            id: String,
            settings: EngineSettings,
            control: TaskControl,
            segmentCapFor: (String) -> Int,
        ): TaskOutcome {
            val entity = store.getById(id) ?: return TaskOutcome.Canceled
            if (control.cancelRequested) return TaskOutcome.Canceled
            store.updateStatus(id, DownloadStatus.CONNECTING)
            return when (Protocols.schemeOf(entity.url)) {
                Protocols.FTP, Protocols.FTPS -> executeFtp(entity, settings, control)
                Protocols.SFTP -> executeSftp(entity, settings, control)
                Protocols.CONTENT -> executeContent(entity, settings, control)
                Protocols.MAGNET -> executeTorrent(entity, settings, control)
                else -> {
                    // magnet:, ed2k:, rtmp: ... fail with an actionable, friendly message.
                    if (!Protocols.isHttpUrl(entity.url)) {
                        val scheme = Protocols.schemeOf(entity.url)
                        TaskOutcome.Failed(
                            DownloadError.PROTOCOL_UNSUPPORTED,
                            if (scheme != null) "Protocol '$scheme:' is not supported" else "Not a downloadable URL",
                        )
                    } else {
                        executeHttp(id, settings, control, segmentCapFor)
                    }
                }
            }
        }

        private suspend fun executeHttp(
            id: String,
            settings: EngineSettings,
            control: TaskControl,
            segmentCapFor: (String) -> Int,
        ): TaskOutcome {
            val entity = store.getById(id) ?: return TaskOutcome.Canceled
            if (control.cancelRequested) return TaskOutcome.Canceled

            // HLS routing (roadmap #10): playlist URLs bypass the byte-range pipeline entirely —
            // a Range GET on an .m3u8 can yield a 206-partial playlist that breaks parsing.
            if (HlsSniffer.isHlsUrl(entity.url) || HlsSniffer.isHlsUrl(entity.finalUrl)) {
                return executeHls(id, settings, control)
            }

            val open = openStream(entity, control, requestedStart = if (entity.resumable) entity.downloadedBytes else 0L, settings = settings)
            when (open) {
                is StreamOpen.AuthRequired -> return TaskOutcome.Failed(DownloadError.AUTH_REQUIRED, "Authentication required")
                is StreamOpen.Failure -> return TaskOutcome.Failed(open.error, open.message, open.retryAfterSec)
                StreamOpen.Hls -> return executeHls(id, settings, control)
                is StreamOpen.Opened -> Unit
            }
            val opened = open as StreamOpen.Opened

            // If the server ignored our Range request while we had partial data, restart cleanly.
            if (opened.restartFromZero) {
                resetPartialData(entity.id)
            }
            val startOffset = if (opened.resumableUsed) entity.downloadedBytes else 0L

            // Update metadata discovered during probing.
            val totalBytes =
                when {
                    opened.contentRangeTotal >= 0 -> opened.contentRangeTotal
                    opened.contentLength >= 0 -> opened.contentLength + startOffset
                    else -> -1L
                }
            val bestName = opened.dispositionFilename?.let { FilenameDetector.sanitize(it) } ?: entity.filename
            val updated =
                entity.copy(
                    finalUrl = opened.finalUrl,
                    filename = bestName,
                    mimeType = opened.mimeType ?: entity.mimeType,
                    totalBytes = totalBytes,
                    acceptRanges = opened.serverAcceptsRanges,
                    eTag = opened.eTag ?: entity.eTag,
                    lastModified = opened.lastModified ?: entity.lastModified,
                    resumable = opened.serverAcceptsRanges || opened.resumableUsed,
                )
            store.update(updated)

            // Pre-flight storage safety: staging + final copy transiently need up to 2x the file
            // size on disk. Pause instead of crashing mid-write when space is insufficient.
            if (totalBytes > 0) {
                val alreadyOnDisk = if (opened.resumableUsed) entity.downloadedBytes else 0L
                if (!storage.hasFreeSpace(2L * totalBytes - alreadyOnDisk)) {
                    opened.response.close()
                    return TaskOutcome.Paused(PauseReason.LOW_STORAGE)
                }
            }

            if (totalBytes == 0L) {
                opened.response.close()
                return finalizeDownload(updated, emptyStagingFile(updated), settings)
            }

            val segmentsWanted = resolveSegments(updated, totalBytes, opened.serverAcceptsRanges, settings, segmentCapFor)
            var usedMulti = false
            val outcome =
                if (segmentsWanted > 1 && totalBytes > 0 && opened.serverAcceptsRanges) {
                    opened.response.close()
                    usedMulti = true
                    runMultiSegment(updated, totalBytes, segmentsWanted, settings, control)
                } else {
                    runTransfer(
                        entity = updated,
                        source =
                            TransferSource(
                                resumableUsed = opened.resumableUsed,
                                stream = {
                                    val body =
                                        opened.response.body
                                            ?: throw java.io.IOException("Empty response body")
                                    body.byteStream()
                                },
                                close = { opened.response.close() },
                            ),
                        startOffset = startOffset,
                        totalBytes = totalBytes,
                        settings = settings,
                        control = control,
                    )
                }
            if (outcome != TaskOutcome.Completed) return outcome
            if (usedMulti) {
                // Multi-segment path already merged the parts into the staging file.
                val fresh = store.getById(id) ?: return TaskOutcome.Canceled
                val merged = storage.stagingFile(fresh.id, fresh.filename)
                if (fresh.totalBytes > 0 && merged.length() != fresh.totalBytes) {
                    return TaskOutcome.Failed(DownloadError.IO_ERROR, "Size mismatch: ${merged.length()} of ${fresh.totalBytes}")
                }
                return finalizeDownload(fresh, merged, settings)
            }
            return promoteAndFinalize(id, settings)
        }

        /** Promotes the single-stream part file to staging and runs verification + finalization. */
        private suspend fun promoteAndFinalize(
            id: String,
            settings: EngineSettings,
        ): TaskOutcome {
            val fresh = store.getById(id) ?: return TaskOutcome.Canceled
            val staged = storage.stagingFile(fresh.id, fresh.filename)
            val part = storage.partFile(fresh.id, 0)
            if (!part.exists()) {
                return TaskOutcome.Failed(DownloadError.IO_ERROR, "Downloaded data missing on disk")
            }
            staged.parentFile?.mkdirs()
            if (staged.exists()) staged.delete()
            if (!part.renameTo(staged)) {
                part.copyTo(staged, overwrite = true)
                part.delete()
            }
            if (fresh.totalBytes > 0 && staged.length() != fresh.totalBytes) {
                return TaskOutcome.Failed(DownloadError.IO_ERROR, "Size mismatch: ${staged.length()} of ${fresh.totalBytes}")
            }
            return finalizeDownload(fresh, staged, settings)
        }

        // region Non-HTTP protocols (FTP/FTPS, SFTP, content://)

        /** Shared pre-transfer metadata update for protocol transports. */
        private suspend fun prepareRemoteEntity(
            entity: DownloadEntity,
            size: Long,
            filenameHint: String?,
            acceptsRanges: Boolean,
        ): DownloadEntity {
            val totalBytes = if (size >= 0) size else -1L
            val bestName = filenameHint?.let { FilenameDetector.sanitize(it) }?.takeIf { it.isNotBlank() } ?: entity.filename
            val updated =
                entity.copy(
                    finalUrl = entity.url,
                    filename = bestName,
                    totalBytes = totalBytes,
                    acceptRanges = acceptsRanges,
                    resumable = acceptsRanges,
                )
            store.update(updated)
            return updated
        }

        private suspend fun executeFtp(
            entity: DownloadEntity,
            settings: EngineSettings,
            control: TaskControl,
        ): TaskOutcome {
            val connectMs = settings.connectTimeoutSec * 1000
            val readMs = settings.readTimeoutSec * 1000
            val session =
                try {
                    ftpTransport.connect(entity.url, connectMs, readMs)
                } catch (e: FtpAuthException) {
                    return TaskOutcome.Failed(DownloadError.AUTH_REQUIRED, e.message)
                } catch (e: java.io.IOException) {
                    return TaskOutcome.Failed(DownloadError.SERVER_ERROR, e.message)
                }
            session.use { ftp ->
                val startOffset =
                    if (entity.resumable && entity.downloadedBytes > 0 && ftp.restSupported) entity.downloadedBytes else 0L
                if (startOffset == 0L && entity.downloadedBytes > 0) resetPartialData(entity.id)
                val updated = prepareRemoteEntity(entity, ftp.size, ftp.filenameHint, ftp.restSupported)
                val totalBytes = updated.totalBytes
                if (totalBytes > 0 && !storage.hasFreeSpace(2L * totalBytes - startOffset)) {
                    return TaskOutcome.Paused(PauseReason.LOW_STORAGE)
                }
                if (totalBytes == 0L) {
                    return finalizeDownload(updated, emptyStagingFile(updated), settings)
                }
                val outcome =
                    runTransfer(
                        entity = updated,
                        source =
                            TransferSource(
                                resumableUsed = startOffset > 0,
                                stream = { ftp.openStreamAt(startOffset) },
                                close = { ftp.close() },
                            ),
                        startOffset = startOffset,
                        totalBytes = totalBytes,
                        settings = settings,
                        control = control,
                    )
                return if (outcome == TaskOutcome.Completed) promoteAndFinalize(entity.id, settings) else outcome
            }
        }

        private suspend fun executeSftp(
            entity: DownloadEntity,
            settings: EngineSettings,
            control: TaskControl,
        ): TaskOutcome {
            val connectMs = settings.connectTimeoutSec * 1000
            val readMs = settings.readTimeoutSec * 1000
            val session =
                try {
                    sftpTransport.connect(entity.url, connectMs, readMs)
                } catch (e: SftpAuthException) {
                    return TaskOutcome.Failed(DownloadError.AUTH_REQUIRED, e.message)
                } catch (e: java.io.IOException) {
                    return TaskOutcome.Failed(DownloadError.SERVER_ERROR, e.message)
                }
            session.use { sftp ->
                val startOffset =
                    if (entity.resumable && entity.downloadedBytes > 0 && sftp.size >= 0 && entity.downloadedBytes < sftp.size) {
                        entity.downloadedBytes
                    } else {
                        0L
                    }
                if (startOffset == 0L && entity.downloadedBytes > 0) resetPartialData(entity.id)
                val updated = prepareRemoteEntity(entity, sftp.size, sftp.filenameHint, acceptsRanges = true)
                val totalBytes = updated.totalBytes
                if (totalBytes > 0 && !storage.hasFreeSpace(2L * totalBytes - startOffset)) {
                    return TaskOutcome.Paused(PauseReason.LOW_STORAGE)
                }
                if (totalBytes == 0L) {
                    return finalizeDownload(updated, emptyStagingFile(updated), settings)
                }
                val outcome =
                    runTransfer(
                        entity = updated,
                        source =
                            TransferSource(
                                resumableUsed = startOffset > 0,
                                stream = { sftp.openStreamAt(startOffset) },
                                close = { sftp.close() },
                            ),
                        startOffset = startOffset,
                        totalBytes = totalBytes,
                        settings = settings,
                        control = control,
                    )
                return if (outcome == TaskOutcome.Completed) promoteAndFinalize(entity.id, settings) else outcome
            }
        }

        private suspend fun executeContent(
            entity: DownloadEntity,
            settings: EngineSettings,
            control: TaskControl,
        ): TaskOutcome {
            val streams =
                contentStreams ?: return TaskOutcome.Failed(DownloadError.PROTOCOL_UNSUPPORTED, "content:// unavailable")
            val uri =
                CookieScopePolicy.safeUri(entity.url)
                    ?: return TaskOutcome.Failed(DownloadError.INVALID_URL, "Malformed content URI")
            if (entity.downloadedBytes > 0) resetPartialData(entity.id)
            val size = streams.size(uri)
            val updated = prepareRemoteEntity(entity, size, null, acceptsRanges = false)
            val totalBytes = updated.totalBytes
            if (totalBytes > 0 && !storage.hasFreeSpace(2L * totalBytes)) {
                return TaskOutcome.Paused(PauseReason.LOW_STORAGE)
            }
            if (totalBytes == 0L) {
                return finalizeDownload(updated, emptyStagingFile(updated), settings)
            }
            val outcome =
                runTransfer(
                    entity = updated,
                    source =
                        TransferSource(
                            resumableUsed = false,
                            stream = { streams.open(uri) ?: throw java.io.IOException("Cannot open $uri") },
                            close = {},
                        ),
                    startOffset = 0L,
                    totalBytes = totalBytes,
                    settings = settings,
                    control = control,
                )
            return if (outcome == TaskOutcome.Completed) promoteAndFinalize(entity.id, settings) else outcome
        }

        // endregion

        // region BitTorrent (magnet links)

        /**
         * Runs a magnet task through [TorrentEngine]: add to the libtorrent session, poll the
         * handle every [TORRENT_POLL_MS] (progress writes, pause/cancel/network/storage checks,
         * metadata timeout) and finalize the payload into the user destination when finished.
         */
        private suspend fun executeTorrent(
            entity: DownloadEntity,
            settings: EngineSettings,
            control: TaskControl,
        ): TaskOutcome {
            val engine =
                torrentEngine
                    ?: return TaskOutcome.Failed(DownloadError.PROTOCOL_UNSUPPORTED, "BitTorrent engine unavailable")
            val saveDir = storage.torrentDir(entity.id)
            if (!engine.addMagnet(entity.url, saveDir)) {
                return TaskOutcome.Failed(DownloadError.TORRENT_FAILED, "The BitTorrent engine could not start on this device")
            }
            // A paused handle must resume when the task is re-run after a USER pause.
            engine.resume(entity.url)

            // Show the dn= name immediately; the real torrent name arrives with the metadata.
            val dn = MagnetUri.displayName(entity.url)
            val placeholder = torrentPlaceholder(entity.url)
            if (!dn.isNullOrBlank() && entity.filename.startsWith(placeholder)) {
                store.updateMeta(entity.id, entity.url, FilenameDetector.sanitize(dn))
            }

            var metadataWaitMs = 0L
            var lastWritten = -1L
            while (true) {
                delay(TORRENT_POLL_MS)
                if (control.cancelRequested) {
                    engine.remove(entity.url)
                    return TaskOutcome.Canceled
                }
                if (control.pauseRequested) {
                    engine.pause(entity.url)
                    store.updateProgress(entity.id, currentTorrentBytes(engine, entity), lastTotalBytes(engine, entity))
                    return TaskOutcome.Paused(PauseReason.USER)
                }
                if (!networkMonitor.isOnline.value) {
                    engine.pause(entity.url)
                    return TaskOutcome.Paused(PauseReason.NO_NETWORK)
                }
                val snap = engine.snapshot(entity.url)
                if (snap == null) {
                    metadataWaitMs += TORRENT_POLL_MS
                    if (metadataWaitMs >= TORRENT_METADATA_TIMEOUT_MS) {
                        engine.remove(entity.url)
                        return TaskOutcome.Failed(DownloadError.TORRENT_METADATA_TIMEOUT, "No peers answered the magnet link")
                    }
                    continue
                }
                snap.errorMessage?.let {
                    engine.remove(entity.url)
                    return TaskOutcome.Failed(DownloadError.TORRENT_FAILED, it)
                }
                if (snap.state == TorrentRunState.FETCHING_METADATA || snap.totalWanted <= 0L) {
                    metadataWaitMs += TORRENT_POLL_MS
                    if (metadataWaitMs >= TORRENT_METADATA_TIMEOUT_MS) {
                        engine.remove(entity.url)
                        return TaskOutcome.Failed(DownloadError.TORRENT_METADATA_TIMEOUT, "Torrent metadata did not arrive in time")
                    }
                    continue
                }
                val fresh = store.getById(entity.id) ?: return TaskOutcome.Canceled
                // Once metadata is known, prefer the real torrent name while the task still
                // carries the auto-generated magnet placeholder.
                if (fresh.filename.startsWith(placeholder) && !snap.name.isNullOrBlank() &&
                    !snap.name.equals(MagnetUri.infoHashHex(entity.url), ignoreCase = true)
                ) {
                    store.updateMeta(entity.id, fresh.url, FilenameDetector.sanitize(snap.name!!))
                }
                // Payload is written in place (1x); the final promotion copy needs a second 1x.
                if (!storage.hasFreeSpace((2L * snap.totalWanted - snap.totalWantedDone).coerceAtLeast(0L))) {
                    engine.pause(entity.url)
                    return TaskOutcome.Paused(PauseReason.LOW_STORAGE)
                }
                if (snap.totalWantedDone != lastWritten) {
                    lastWritten = snap.totalWantedDone
                    store.updateProgress(entity.id, snap.totalWantedDone, snap.totalWanted)
                }
                if (snap.state == TorrentRunState.FINISHED || snap.totalWantedDone >= snap.totalWanted) {
                    store.updateProgress(entity.id, snap.totalWanted, snap.totalWanted)
                    // Never seed from a mobile device: stop the moment we complete.
                    engine.pause(entity.url)
                    break
                }
            }
            return finalizeTorrent(entity, saveDir, settings)
        }

        private fun currentTorrentBytes(
            engine: TorrentEngine,
            entity: DownloadEntity,
        ): Long = engine.snapshot(entity.url)?.totalWantedDone ?: 0L

        private fun lastTotalBytes(
            engine: TorrentEngine,
            entity: DownloadEntity,
        ): Long = engine.snapshot(entity.url)?.totalWanted?.takeIf { it > 0 } ?: -1L

        /** Promotes the finished torrent payload into the user destination. */
        private suspend fun finalizeTorrent(
            entity: DownloadEntity,
            saveDir: File,
            settings: EngineSettings,
        ): TaskOutcome {
            store.updateStatus(entity.id, DownloadStatus.MERGING)
            val fresh = store.getById(entity.id) ?: return TaskOutcome.Canceled
            val files = TorrentFiles.collect(saveDir)
            if (files.isEmpty()) {
                return TaskOutcome.Failed(DownloadError.IO_ERROR, "Torrent finished without payload files")
            }
            return if (files.size == 1) {
                val file = files.first()
                // Prefer the real payload name so MediaStore gets a proper extension/type.
                val name = FilenameDetector.sanitize(file.name).ifBlank { fresh.filename }
                val staged = storage.stagingFile(fresh.id, name)
                staged.parentFile?.mkdirs()
                file.copyTo(staged, overwrite = true)
                val updated =
                    fresh.copy(
                        filename = name,
                        totalBytes = staged.length(),
                        mimeType = TorrentFiles.guessMime(name) ?: fresh.mimeType,
                    )
                store.update(updated)
                val outcome = finalizeDownload(updated, staged, settings)
                if (outcome == TaskOutcome.Completed) storage.cleanup(fresh.id)
                outcome
            } else {
                finalizeTorrentFolder(fresh, files, settings)
            }
        }

        /** Multi-file torrent: copy every payload file into Downloads/Arc/<folder>/ and complete. */
        private suspend fun finalizeTorrentFolder(
            fresh: DownloadEntity,
            files: List<File>,
            settings: EngineSettings,
        ): TaskOutcome {
            val folderName = FilenameDetector.sanitize(fresh.filename).ifBlank { "Torrent" }
            return try {
                val uris = storage.finalizeFolder(files, folderName, fresh.destinationUri)
                val now = System.currentTimeMillis()
                store.update(
                    fresh.copy(
                        status = DownloadStatus.COMPLETED,
                        finalUri = uris.joinToString("\n"),
                        totalBytes = TorrentFiles.totalBytes(files),
                        errorCode = null,
                        errorMessage = null,
                        updatedAt = now,
                        completedAt = now,
                    ),
                )
                storage.cleanup(fresh.id)
                TaskOutcome.Completed
            } catch (e: java.io.IOException) {
                store.updateStatus(fresh.id, DownloadStatus.FAILED, DownloadError.IO_ERROR.name, e.message)
                TaskOutcome.Failed(DownloadError.IO_ERROR, e.message)
            }
        }

        /** Auto filename for a magnet before its real name is known. */
        private fun torrentPlaceholder(magnetUri: String): String {
            val hash = MagnetUri.infoHashHex(magnetUri)
            return if (hash != null) "magnet-${hash.take(12)}" else "magnet"
        }

        // endregion

        // region Shared transfer loop

        /**
         * Copies a remote stream into part_0 with pause/cancel handling, bandwidth limiting,
         * periodic progress + storage/network checks. Shared by HTTP single-stream and all
         * non-HTTP protocol transports.
         */
        private suspend fun runTransfer(
            entity: DownloadEntity,
            source: TransferSource,
            startOffset: Long,
            totalBytes: Long,
            settings: EngineSettings,
            control: TaskControl,
        ): TaskOutcome {
            val tempFile = storage.partFile(entity.id, 0)
            var append = source.resumableUsed && startOffset > 0
            if (append && tempFile.length() != startOffset) {
                // Partial data on disk does not match the offset we resumed from: restart safely.
                if (tempFile.length() > startOffset) {
                    java.io.RandomAccessFile(tempFile, "rw").use { it.setLength(startOffset) }
                } else {
                    append = false
                    tempFile.delete()
                    return TaskOutcome.Failed(DownloadError.RESOURCE_CHANGED, "Partial data missing on disk")
                }
            }
            if (!append) tempFile.delete()
            val limiter = BandwidthLimiter { connectedLimit(speedLimitHolder.limitKBps) }
            var written = if (append) startOffset else 0L
            var lastFlush = System.currentTimeMillis()
            var lastSpaceCheck = lastFlush
            try {
                FileOutputStream(tempFile, append).use { out ->
                    source.stream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            if (control.cancelRequested) return TaskOutcome.Canceled
                            if (control.pauseRequested) {
                                store.updateProgress(entity.id, written, totalBytes)
                                return TaskOutcome.Paused(PauseReason.USER)
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            written += read
                            limiter.onBytes(read)
                            val now = System.currentTimeMillis()
                            if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                                lastFlush = now
                                store.updateProgress(entity.id, written, totalBytes)
                                if (now - lastSpaceCheck >= SPACE_CHECK_INTERVAL_MS) {
                                    lastSpaceCheck = now
                                    // Staging + final copy can transiently require 2x the file size.
                                    val remaining =
                                        if (totalBytes > 0) 2L * totalBytes - written else 64L * 1024L * 1024L
                                    if (!storage.hasFreeSpace(remaining)) {
                                        store.updateProgress(entity.id, written, totalBytes)
                                        return TaskOutcome.Paused(PauseReason.LOW_STORAGE)
                                    }
                                }
                                if (!networkMonitor.isOnline.value) {
                                    store.updateProgress(entity.id, written, totalBytes)
                                    return TaskOutcome.Paused(PauseReason.NO_NETWORK)
                                }
                            }
                        }
                    }
                }
                store.updateProgress(entity.id, written, totalBytes)
                if (totalBytes > 0 && written != totalBytes) {
                    return TaskOutcome.Failed(DownloadError.IO_ERROR, "Connection closed early ($written/$totalBytes)")
                }
                return TaskOutcome.Completed
            } catch (e: java.io.IOException) {
                return TaskOutcome.Failed(DownloadError.IO_ERROR, e.message)
            } finally {
                source.close()
            }
        }

        // endregion

        // region Multi segment

        private suspend fun runMultiSegment(
            entity: DownloadEntity,
            totalBytes: Long,
            segmentCount: Int,
            settings: EngineSettings,
            control: TaskControl,
        ): TaskOutcome {
            val ranges = SegmentPlanner.plan(totalBytes, segmentCount)
            var segments = store.segmentsFor(entity.id)
            val planChanged =
                segments.size != ranges.size ||
                    segments.any { seg -> ranges.getOrNull(seg.index)?.let { it.start != seg.startByte || it.endInclusive != seg.endByte } ?: true }
            if (planChanged) {
                store.deleteSegments(entity.id)
                for (i in ranges.indices) storage.partFile(entity.id, i).delete()
                segments = emptyList()
            }
            if (segments.isEmpty()) {
                segments =
                    ranges.mapIndexed { index, range ->
                        SegmentEntity(
                            downloadId = entity.id,
                            index = index,
                            startByte = range.start,
                            endByte = range.endInclusive,
                            downloadedBytes = 0L,
                            status = DownloadStatus.QUEUED,
                        )
                    }
                store.saveSegments(segments)
            }
            // Reconcile on-disk part files with recorded progress (crash recovery).
            segments =
                segments.map { seg ->
                    val onDisk = storage.reconcilePart(entity.id, seg.index, seg.downloadedBytes)
                    if (onDisk != seg.downloadedBytes) {
                        val fixed = seg.copy(downloadedBytes = onDisk)
                        store.saveSegments(listOf(fixed))
                        fixed
                    } else {
                        seg
                    }
                }

            val limiter = BandwidthLimiter { connectedLimit(speedLimitHolder.limitKBps) }
            val results =
                coroutineScope {
                    // Live aggregate progress: each segment only updates its own row, so the
                    // downloads-table aggregate would otherwise stay frozen until every segment
                    // finished. Publish the segment-bytes sum on a fixed cadence so the UI,
                    // notifications and speed estimates update in real time.
                    val aggregator =
                        launch {
                            while (true) {
                                delay(AGGREGATE_PROGRESS_INTERVAL_MS)
                                store.updateProgress(entity.id, store.totalSegmentBytes(entity.id), totalBytes)
                            }
                        }
                    val results =
                        segments.map { seg ->
                            async(Dispatchers.IO) {
                                downloadSegment(entity, seg, settings, control, limiter)
                            }
                        }.awaitAll()
                    aggregator.cancel()
                    results
                }

            // Aggregate progress one last time.
            store.updateProgress(entity.id, store.totalSegmentBytes(entity.id), totalBytes)

            results.forEach { result ->
                when (result) {
                    is SegmentResult.Canceled -> return TaskOutcome.Canceled
                    is SegmentResult.Paused -> return TaskOutcome.Paused(result.reason)
                    is SegmentResult.AuthRequired -> return TaskOutcome.Failed(DownloadError.AUTH_REQUIRED, "Session expired")
                    is SegmentResult.Failed -> return TaskOutcome.Failed(result.error, result.message, result.retryAfterSec)
                    is SegmentResult.Completed -> Unit
                }
            }
            // All segments complete: merge parts into the staging file.
            store.updateStatus(entity.id, DownloadStatus.MERGING)
            val staged = storage.stagingFile(entity.id, store.getById(entity.id)?.filename ?: entity.filename)
            val parts = ranges.indices.map { storage.partFile(entity.id, it) }
            return try {
                MergeManager.merge(parts, staged)
                if (staged.length() != totalBytes) {
                    TaskOutcome.Failed(DownloadError.MERGE_FAILED, "Merged size ${staged.length()} != $totalBytes")
                } else {
                    // Free the part files immediately: peak disk usage halves before the
                    // finalization copy into MediaStore/SAF.
                    parts.forEach { it.delete() }
                    TaskOutcome.Completed
                }
            } catch (e: java.io.IOException) {
                TaskOutcome.Failed(DownloadError.MERGE_FAILED, e.message)
            }
        }

        private suspend fun downloadSegment(
            entity: DownloadEntity,
            segment: SegmentEntity,
            settings: EngineSettings,
            control: TaskControl,
            limiter: BandwidthLimiter,
        ): SegmentResult {
            var downloaded = segment.downloadedBytes
            var attempt = 0
            var refreshedUrl = false
            var current = entity
            val maxAttempts = (settings.retryAttempts + 1).coerceAtLeast(1)
            while (attempt < maxAttempts) {
                if (control.cancelRequested) return SegmentResult.Canceled
                if (control.pauseRequested) {
                    store.updateSegmentProgress(entity.id, segment.index, downloaded, DownloadStatus.PAUSED)
                    return SegmentResult.Paused(PauseReason.USER)
                }
                val start = segment.startByte + downloaded
                if (start > segment.endByte) {
                    store.updateSegmentProgress(entity.id, segment.index, downloaded, DownloadStatus.COMPLETED)
                    return SegmentResult.Completed
                }
                store.updateSegmentProgress(entity.id, segment.index, downloaded, DownloadStatus.DOWNLOADING)
                val ifRange = if (downloaded > 0) entity.eTag else null
                val response =
                    try {
                        executeRequest(current, start, segment.endByte, ifRange, settings)
                    } catch (e: java.io.IOException) {
                        attempt++
                        if (attempt >= maxAttempts) return SegmentResult.Failed(DownloadError.IO_ERROR, e.message)
                        kotlinx.coroutines.delay(backoff.delayFor(attempt))
                        continue
                    }
                response.use { resp ->
                    when {
                        resp.code == 206 -> {
                            val range = ContentRangeParser.parse(resp.header("Content-Range"))
                            if (range == null || range.first != start) {
                                return SegmentResult.Failed(DownloadError.SEGMENT_MISMATCH, "Server returned unexpected range")
                            }
                            val written = writeSegmentBody(current, segment, resp, downloaded, control, limiter)
                            return when {
                                written == WriteResult.Canceled -> SegmentResult.Canceled
                                written == WriteResult.Paused -> SegmentResult.Paused(PauseReason.USER)
                                written == WriteResult.NoNetwork -> SegmentResult.Paused(PauseReason.NO_NETWORK)
                                written == WriteResult.LowStorage -> SegmentResult.Paused(PauseReason.LOW_STORAGE)
                                written == WriteResult.Truncated -> SegmentResult.Failed(DownloadError.IO_ERROR, "Segment closed early")
                                written is WriteResult.Done -> {
                                    downloaded = segment.length
                                    store.updateSegmentProgress(entity.id, segment.index, downloaded, DownloadStatus.COMPLETED)
                                    SegmentResult.Completed
                                }
                                else -> SegmentResult.Failed(DownloadError.UNKNOWN, "Unexpected write result")
                            }
                        }
                        resp.code == 200 -> {
                            if (downloaded > 0) {
                                SegmentResult.Failed(DownloadError.RESOURCE_CHANGED, "Server restarted the file")
                            } else {
                                SegmentResult.Failed(DownloadError.RANGE_NOT_SUPPORTED, "Server ignored range request")
                            }
                        }
                        // 401/403/404/410 on a signed CDN URL (GitHub release assets are signed
                        // and expire) usually means the URL went stale, not that auth is needed.
                        // Re-resolve the ORIGINAL url (fresh redirect chain) once and retry.
                        resp.code == 401 || resp.code == 403 || resp.code == 404 || resp.code == 410 -> {
                            if (!refreshedUrl) {
                                refreshedUrl = true
                                val fresh = refreshFinalUrl(entity, settings)
                                if (fresh != null && fresh != (entity.finalUrl ?: entity.url)) {
                                    current = entity.copy(finalUrl = fresh)
                                    store.update(store.getById(entity.id)?.copy(finalUrl = fresh) ?: current)
                                    return@use // retry loop with the freshly signed URL
                                }
                            }
                            return if (resp.code == 401 || resp.code == 403) {
                                SegmentResult.AuthRequired
                            } else {
                                SegmentResult.Failed(DownloadError.EXPIRED_LINK, "HTTP ${resp.code}")
                            }
                        }
                        resp.code == 429 || resp.code == 503 ->
                            SegmentResult.Failed(
                                DownloadError.RATE_LIMITED,
                                "HTTP ${resp.code}",
                                resp.header("Retry-After")?.toLongOrNull(),
                            )
                        resp.code == 416 -> SegmentResult.Failed(DownloadError.SEGMENT_MISMATCH, "HTTP 416 invalid range")
                        resp.code in 500..599 -> SegmentResult.Failed(DownloadError.SERVER_ERROR, "HTTP ${resp.code}")
                        else -> SegmentResult.Failed(DownloadError.SERVER_ERROR, "HTTP ${resp.code}")
                    }
                }
                attempt++
                if (attempt < maxAttempts) kotlinx.coroutines.delay(backoff.delayFor(attempt))
            }
            return SegmentResult.Failed(DownloadError.SERVER_ERROR, "Exhausted retries for segment ${segment.index}")
        }

        /**
         * Re-follows the redirect chain from the original URL without keeping the body, yielding
         * a freshly signed final URL (used when a CDN-signed link such as GitHub release assets
         * expires mid-download). Returns null when the chain cannot be resolved.
         */
        private suspend fun refreshFinalUrl(
            entity: DownloadEntity,
            settings: EngineSettings,
        ): String? {
            var url = entity.url
            var hops = 0
            while (hops < MAX_REDIRECTS) {
                val request = buildRequest(entity, url, 0, -1, null)
                val response =
                    try {
                        httpClientFor(entity, settings).newCall(request).execute()
                    } catch (_: java.io.IOException) {
                        return null
                    }
                when {
                    response.code in 300..399 -> {
                        val location = response.header("Location")
                        response.close()
                        val next = location?.let { resolveRedirect(url, it) } ?: return null
                        if (!CookieScopePolicy.isHttpUrl(next)) return null
                        url = next
                        hops++
                    }
                    else -> {
                        response.close()
                        return url
                    }
                }
            }
            return null
        }

        private sealed interface WriteResult {
            data class Done(val bytes: Long) : WriteResult

            data object Canceled : WriteResult

            data object Paused : WriteResult

            data object NoNetwork : WriteResult

            data object LowStorage : WriteResult

            data object Truncated : WriteResult
        }

        private sealed interface SegmentResult {
            data object Completed : SegmentResult

            data object Canceled : SegmentResult

            data class Paused(val reason: PauseReason) : SegmentResult

            data object AuthRequired : SegmentResult

            data class Failed(val error: DownloadError, val message: String?, val retryAfterSec: Long? = null) : SegmentResult
        }

        private suspend fun writeSegmentBody(
            entity: DownloadEntity,
            segment: SegmentEntity,
            response: okhttp3.Response,
            alreadyDownloaded: Long,
            control: TaskControl,
            limiter: BandwidthLimiter,
        ): WriteResult {
            val partFile = storage.partFile(entity.id, segment.index)
            val expectedEnd = segment.endByte
            var written = alreadyDownloaded
            var lastFlush = System.currentTimeMillis()
            val body = response.body ?: return WriteResult.Truncated
            try {
                java.io.FileOutputStream(partFile, true).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            if (control.cancelRequested) return WriteResult.Canceled
                            if (control.pauseRequested) {
                                store.updateSegmentProgress(entity.id, segment.index, written, DownloadStatus.PAUSED)
                                return WriteResult.Paused
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            // Guard against writing beyond the segment boundary.
                            val allowed = minOf(read.toLong(), expectedEnd - (segment.startByte + written) + 1).toInt()
                            if (allowed > 0) {
                                out.write(buffer, 0, allowed)
                                written += allowed
                                limiter.onBytes(allowed)
                            }
                            if (segment.startByte + written > expectedEnd) {
                                // Overshoot: server sent more bytes than planned; keep only the planned window.
                                break
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                                lastFlush = now
                                store.updateSegmentProgress(entity.id, segment.index, written, DownloadStatus.DOWNLOADING)
                                if (!networkMonitor.isOnline.value) return WriteResult.NoNetwork
                                // Aggregate-aware check: remaining bytes across all segments plus
                                // the staging+final copy headroom (2x total when known).
                                val remainingAll =
                                    if (entity.totalBytes > 0) {
                                        entity.totalBytes * 2 - store.totalSegmentBytes(entity.id)
                                    } else {
                                        64L * 1024L * 1024L
                                    }
                                if (!storage.hasFreeSpace(remainingAll)) return WriteResult.LowStorage
                            }
                        }
                    }
                }
                store.updateSegmentProgress(entity.id, segment.index, written, DownloadStatus.DOWNLOADING)
                return if (segment.startByte + written == expectedEnd + 1) {
                    WriteResult.Done(written)
                } else {
                    WriteResult.Truncated
                }
            } catch (e: java.io.IOException) {
                return WriteResult.Truncated
            }
        }

        // endregion

        // region HLS (M3U8)

        /**
         * HLS/M3U8 pipeline (roadmap #10): playlist fetch (+ master variant selection), AES-128
         * segment decryption, 3-way parallel segment download into the task's hls temp dir and an
         * ordered concat merge, then the shared verification/finalization path. Per-segment
         * resume: finished `NNNNNN.part` files are skipped on re-run after pause/app kill.
         */
        private suspend fun executeHls(
            id: String,
            settings: EngineSettings,
            control: TaskControl,
        ): TaskOutcome {
            val entity = store.getById(id) ?: return TaskOutcome.Canceled
            if (control.cancelRequested) return TaskOutcome.Canceled
            store.updateStatus(id, DownloadStatus.CONNECTING)
            val playlistUrl = entity.finalUrl ?: entity.url
            // The HLS pipeline follows redirects again: the playlist may hop to a CDN whose
            // response URL is the base for resolving relative segment URLs (done inside
            // HlsDownloader against response.request.url).
            val client =
                httpClientFor(entity, settings)
                    .newBuilder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
            // Header factory mirroring buildRequest() (UA/referer/cookies) minus Range headers.
            // Cookie/referer scope is evaluated against the playlist URL so session cookies
            // never leak to cross-host segment/key CDNs — same policy as the byte-range path.
            val downloader =
                HlsDownloader(client) {
                    val builder = okhttp3.Request.Builder().header("User-Agent", entity.userAgent ?: DEFAULT_USER_AGENT)
                    val referer = entity.referer
                    if (!referer.isNullOrBlank() && CookieScopePolicy.evaluate(referer, playlistUrl).allowReferer) {
                        builder.header("Referer", referer)
                    }
                    cookiesFor(entity, playlistUrl)?.let { builder.header("Cookie", it) }
                    builder
                }
            val result =
                downloader.download(
                    playlistUrl = playlistUrl,
                    tempDir = storage.hlsDir(id),
                    control = control,
                    onStatus = { store.updateStatus(id, it) },
                    onPlanned = { count -> store.getById(id)?.let { store.update(it.copy(segmentsPlanned = count)) } },
                    onProgress = { done, total -> store.updateProgress(id, done, total) },
                    isOnline = { networkMonitor.isOnline.value },
                    hasFreeSpace = { storage.hasFreeSpace(it) },
                    limiter = BandwidthLimiter { connectedLimit(speedLimitHolder.limitKBps) },
                )
            return when (result) {
                is HlsResult.Completed -> finalizeHls(id, result, settings)
                is HlsResult.Paused -> TaskOutcome.Paused(result.reason)
                HlsResult.Canceled -> TaskOutcome.Canceled
                is HlsResult.Failed -> TaskOutcome.Failed(result.error, result.message)
            }
        }

        /** Moves the merged HLS output into staging under the container-correct name and finalizes. */
        private suspend fun finalizeHls(
            id: String,
            completed: HlsResult.Completed,
            settings: EngineSettings,
        ): TaskOutcome {
            store.updateStatus(id, DownloadStatus.MERGING)
            val fresh = store.getById(id) ?: return TaskOutcome.Canceled
            val name = hlsOutputName(fresh.filename, completed.extension)
            val staged = storage.stagingFile(id, name)
            staged.parentFile?.mkdirs()
            if (staged.exists()) staged.delete()
            val merged = completed.mergedFile
            if (!merged.renameTo(staged)) {
                merged.copyTo(staged, overwrite = true)
                merged.delete()
            }
            val updated =
                fresh.copy(
                    filename = name,
                    totalBytes = staged.length(),
                    downloadedBytes = staged.length(),
                    mimeType = if (completed.extension == "mp4") "video/mp4" else "video/mp2t",
                    resumable = false,
                )
            store.update(updated)
            return finalizeDownload(updated, staged, settings)
        }

        /**
         * Swaps the .m3u8 task filename for the real container extension (.ts/.mp4). A
         * pre-existing non-matching extension is replaced too: the merged bytes ARE the
         * container, so the name must not mislabel it.
         */
        private fun hlsOutputName(
            raw: String,
            extension: String,
        ): String {
            val name = FilenameDetector.sanitize(raw)
            val withoutM3u8 = if (name.endsWith(".m3u8", ignoreCase = true)) name.dropLast(5) else name
            val dot = withoutM3u8.lastIndexOf('.')
            val base = if (dot > 0) withoutM3u8.substring(0, dot) else withoutM3u8
            return FilenameDetector.sanitize("$base.$extension").ifBlank { "hls-$extension" }
        }

        /** Peeks the first bytes of a response body without consuming it (best effort). */
        private fun peekHead(response: okhttp3.Response): ByteArray = runCatching { response.peekBody(16L).bytes() }.getOrDefault(ByteArray(0))

        // endregion

        // region HTTP plumbing

        private suspend fun openStream(
            entity: DownloadEntity,
            control: TaskControl,
            requestedStart: Long,
            settings: EngineSettings,
        ): StreamOpen {
            var url = entity.url
            var hops = 0
            while (hops < MAX_REDIRECTS) {
                if (control.cancelRequested) return StreamOpen.Failure(DownloadError.CANCELED, "Canceled")
                if (!networkMonitor.isOnline.value) return StreamOpen.Failure(DownloadError.NETWORK_UNAVAILABLE, "Offline")
                val request = buildRequest(entity, url, requestedStart, -1L, ifRange = if (requestedStart > 0) entity.eTag else null)
                val response =
                    try {
                        httpClientFor(entity, settings).newCall(request).execute()
                    } catch (e: java.io.IOException) {
                        return StreamOpen.Failure(DownloadError.IO_ERROR, e.message)
                    }
                when {
                    response.code in 300..399 -> {
                        val location = response.header("Location")
                        response.close()
                        val next =
                            location?.let { resolveRedirect(url, it) }
                                ?: return StreamOpen.Failure(DownloadError.EXPIRED_LINK, "Redirect without Location")
                        if (!CookieScopePolicy.isHttpUrl(next)) {
                            return StreamOpen.Failure(DownloadError.PROTOCOL_UNSUPPORTED, "Redirect to unsupported scheme")
                        }
                        url = next
                        hops++
                    }
                    response.code == 206 -> {
                        val range = ContentRangeParser.parse(response.header("Content-Range"))
                        if (range == null || range.first != requestedStart) {
                            response.close()
                            return StreamOpen.Failure(DownloadError.SEGMENT_MISMATCH, "Invalid Content-Range on resume")
                        }
                        return StreamOpen.Opened(
                            response = response,
                            finalUrl = url,
                            contentLength = response.body?.contentLength() ?: -1L,
                            contentRangeTotal = ContentRangeParser.totalSize(response.header("Content-Range")),
                            resumableUsed = true,
                            restartFromZero = false,
                            serverAcceptsRanges = true,
                            eTag = response.header("ETag"),
                            lastModified = response.header("Last-Modified"),
                            mimeType = response.header("Content-Type"),
                            dispositionFilename = FilenameDetector.fromDisposition(response.header("Content-Disposition")),
                        )
                    }
                    response.code == 200 -> {
                        val contentType = response.header("Content-Type")
                        val disposition = FilenameDetector.fromDisposition(response.header("Content-Disposition"))
                        // HLS detection must run BEFORE the web-page rejection below: playlists can
                        // arrive with an mpegurl content-type, an .m3u8 final URL (redirect chains
                        // included — this branch sees the last hop), or an #EXTM3U body signature.
                        if (
                            HlsSniffer.isHlsContentType(contentType) ||
                            HlsSniffer.isHlsUrl(url) ||
                            HlsSniffer.looksLikePlaylist(peekHead(response))
                        ) {
                            response.close()
                            return StreamOpen.Hls
                        }
                        // Fail fast when the URL points at a web page instead of a file (e.g. a
                        // GitHub release PAGE link): text/html body, no attachment name and no
                        // file-like extension. Saving it would produce a useless junk file.
                        if (contentType?.substringBefore(';')?.trim()?.equals("text/html", ignoreCase = true) == true &&
                            disposition == null &&
                            !FilenameDetector.urlHasFileExtension(url)
                        ) {
                            response.close()
                            return StreamOpen.Failure(DownloadError.PAGE_NOT_FILE, "URL returned a web page, not a downloadable file")
                        }
                        val ignoredRange = requestedStart > 0
                        return StreamOpen.Opened(
                            response = response,
                            finalUrl = url,
                            contentLength = response.body?.contentLength() ?: -1L,
                            contentRangeTotal = -1L,
                            resumableUsed = false,
                            restartFromZero = ignoredRange,
                            serverAcceptsRanges = RangeSupportDetector.supports(response.headers.toMultimap(), 200),
                            eTag = response.header("ETag"),
                            lastModified = response.header("Last-Modified"),
                            mimeType = contentType,
                            dispositionFilename = disposition,
                        )
                    }
                    response.code == 401 || response.code == 403 -> {
                        response.close()
                        return StreamOpen.AuthRequired
                    }
                    response.code == 429 || response.code == 503 -> {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()
                        response.close()
                        return StreamOpen.Failure(DownloadError.RATE_LIMITED, "HTTP ${response.code}", retryAfter)
                    }
                    response.code == 404 || response.code == 410 -> {
                        response.close()
                        return StreamOpen.Failure(DownloadError.EXPIRED_LINK, "HTTP ${response.code}")
                    }
                    response.code == 416 -> {
                        response.close()
                        return StreamOpen.Failure(DownloadError.SEGMENT_MISMATCH, "HTTP 416 while resuming")
                    }
                    response.code in 500..599 -> {
                        response.close()
                        return StreamOpen.Failure(DownloadError.SERVER_ERROR, "HTTP ${response.code}")
                    }
                    else -> {
                        response.close()
                        return StreamOpen.Failure(DownloadError.SERVER_ERROR, "HTTP ${response.code}")
                    }
                }
            }
            return StreamOpen.Failure(DownloadError.EXPIRED_LINK, "Too many redirects")
        }

        private suspend fun executeRequest(
            entity: DownloadEntity,
            start: Long,
            end: Long,
            ifRange: String?,
            settings: EngineSettings,
        ): okhttp3.Response {
            val request = buildRequest(entity, entity.finalUrl ?: entity.url, start, end, ifRange)
            return httpClientFor(entity, settings).newCall(request).execute()
        }

        private fun buildRequest(
            entity: DownloadEntity,
            url: String,
            startByte: Long,
            endByte: Long,
            ifRange: String?,
        ): okhttp3.Request {
            val builder =
                okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", entity.userAgent ?: DEFAULT_USER_AGENT)
            if (endByte >= startByte && endByte >= 0) {
                builder.header("Range", "bytes=$startByte-$endByte")
            } else if (startByte > 0 || entity.totalBytes > 0 || entity.acceptRanges) {
                builder.header("Range", "bytes=$startByte-")
            }
            if (ifRange != null) {
                builder.header("If-Range", ifRange)
            }
            val referer = entity.referer
            if (!referer.isNullOrBlank() && CookieScopePolicy.evaluate(referer, url).allowReferer) {
                builder.header("Referer", referer)
            }
            val cookies = cookiesFor(entity, url)
            if (!cookies.isNullOrBlank()) {
                builder.header("Cookie", cookies)
            }
            return builder.build()
        }

        private fun cookiesFor(
            entity: DownloadEntity,
            url: String,
        ): String? {
            if (entity.cookies.isNullOrBlank() && !entity.requiresSession) return null
            // Cookies captured for the original URL must never leak to a cross-site redirect
            // target (e.g. github.com session cookies must not be sent to
            // release-assets.githubusercontent.com). Scope check uses the ORIGINAL url.
            if (!CookieScopePolicy.evaluate(entity.url, url).allowCookies) return null
            val live = runCatching { cookieProvider.cookiesFor(url) }.getOrNull()
            return live ?: entity.cookies
        }

        private fun resolveRedirect(
            base: String,
            location: String,
        ): String? = runCatching { base.toHttpUrlOrNull()?.resolve(location)?.toString() }.getOrNull()

        private fun httpClientFor(
            entity: DownloadEntity,
            settings: EngineSettings,
        ): OkHttpClient {
            val builder =
                baseClient.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(true)
                    .connectTimeout(settings.connectTimeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(settings.readTimeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(settings.readTimeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            val isPrivate = entity.source.equals("BROWSER", ignoreCase = true)
            if (isPrivate || entity.requiresSession) {
                // Avoid sharing the default cookie jar semantics; engine manages cookies explicitly.
                builder.cookieJar(CookieJarNoop)
            }
            return builder.build()
        }

        // endregion

        // region Finalization

        private suspend fun finalizeDownload(
            entity: DownloadEntity,
            staged: File,
            settings: EngineSettings,
        ): TaskOutcome {
            store.updateStatus(entity.id, DownloadStatus.VERIFYING)
            val expected = entity.expectedSha256?.let { Checksum.normalize(it) }
            var computed: String? = null
            if (expected != null) {
                computed = Checksum.sha256(staged)
                if (computed != expected) {
                    staged.delete()
                    storage.cleanup(entity.id)
                    store.updateStatus(entity.id, DownloadStatus.FAILED, DownloadError.CHECKSUM_MISMATCH.name, "Checksum mismatch")
                    return TaskOutcome.Failed(DownloadError.CHECKSUM_MISMATCH, "Checksum mismatch")
                }
            } else if (settings.computeSha256) {
                computed = runCatching { Checksum.sha256(staged) }.getOrNull()
            }
            return try {
                val finalUri = storage.finalize(entity, staged)
                val done = store.getById(entity.id) ?: return TaskOutcome.Canceled
                store.update(
                    done.copy(
                        status = DownloadStatus.COMPLETED,
                        finalUri = finalUri,
                        checksumSha256 = computed ?: done.checksumSha256,
                        updatedAt = System.currentTimeMillis(),
                        completedAt = System.currentTimeMillis(),
                        errorCode = null,
                        errorMessage = null,
                    ),
                )
                storage.cleanup(entity.id)
                TaskOutcome.Completed
            } catch (e: java.io.IOException) {
                store.updateStatus(entity.id, DownloadStatus.FAILED, DownloadError.IO_ERROR.name, e.message)
                TaskOutcome.Failed(DownloadError.IO_ERROR, e.message)
            }
        }

        private fun emptyStagingFile(entity: DownloadEntity): File =
            storage.stagingFile(entity.id, entity.filename).apply {
                parentFile?.mkdirs()
                if (exists()) delete()
                createNewFile()
            }

        private suspend fun resetPartialData(id: String) {
            val entity = store.getById(id) ?: return
            storage.cleanup(id)
            store.deleteSegments(id)
            store.update(
                entity.copy(
                    downloadedBytes = 0L,
                    resumable = false,
                    acceptRanges = false,
                ),
            )
            store.updateProgress(id, 0L, entity.totalBytes)
        }

        private suspend fun finalizeOutcome(
            id: String,
            outcome: TaskOutcome,
            settings: EngineSettings,
            control: TaskControl,
        ) {
            val entity = store.getById(id) ?: return
            when (outcome) {
                is TaskOutcome.Paused -> {
                    if (control.cancelRequested) {
                        markCanceled(entity)
                    } else {
                        val status =
                            when (outcome.reason) {
                                PauseReason.USER -> DownloadStatus.PAUSED
                                PauseReason.NO_NETWORK -> if (settings.autoResume) DownloadStatus.WAITING_NETWORK else DownloadStatus.PAUSED
                                PauseReason.LOW_STORAGE -> DownloadStatus.WAITING_STORAGE
                                // Android 15+ stopped the dataSync foreground service (6 h / 24 h quota).
                                // Stay PAUSED so tasks do not auto-restart while the quota is exhausted;
                                // they resume when the user opens the app (foreground resets the timer).
                                PauseReason.QUOTA -> DownloadStatus.PAUSED
                            }
                        store.updateStatus(id, status, null, null)
                    }
                }
                is TaskOutcome.Canceled -> if (entity.status != DownloadStatus.CANCELED) markCanceled(entity)
                is TaskOutcome.Failed -> {
                    if (control.cancelRequested) {
                        markCanceled(entity)
                        return
                    }
                    if (outcome.error == DownloadError.CHECKSUM_MISMATCH) return // already recorded
                    handleFailure(entity, outcome, settings)
                }
                TaskOutcome.Completed -> Unit // handled in finalizeDownload
            }
        }

        suspend fun handleFailure(
            entity: DownloadEntity,
            outcome: TaskOutcome.Failed,
            settings: EngineSettings,
        ) {
            val id = entity.id
            when (outcome.error) {
                DownloadError.RESOURCE_CHANGED, DownloadError.SEGMENT_MISMATCH -> {
                    // Partial data is no longer valid: restart from scratch (bounded attempts).
                    if (settings.autoRetry && entity.retryCount < 2) {
                        resetPartialData(id)
                        store.incrementRetry(id)
                        store.updateStatus(id, DownloadStatus.QUEUED, outcome.error.name, "File changed, restarting")
                    } else {
                        store.updateStatus(id, DownloadStatus.FAILED, outcome.error.name, outcome.message)
                    }
                }
                DownloadError.NETWORK_UNAVAILABLE -> store.updateStatus(id, DownloadStatus.WAITING_NETWORK, null, null)
                DownloadError.RATE_LIMITED -> {
                    if (settings.autoRetry && entity.retryCount < settings.retryAttempts) {
                        store.incrementRetry(id)
                        store.updateStatus(id, DownloadStatus.QUEUED, outcome.error.name, outcome.message)
                    } else {
                        store.updateStatus(id, DownloadStatus.FAILED, outcome.error.name, outcome.message)
                    }
                }
                DownloadError.AUTH_REQUIRED -> store.updateStatus(id, DownloadStatus.AUTH_REQUIRED, outcome.error.name, outcome.message)
                DownloadError.INSUFFICIENT_STORAGE -> store.updateStatus(id, DownloadStatus.WAITING_STORAGE, null, null)
                DownloadError.CANCELED -> markCanceled(entity)
                else -> {
                    val recoverable =
                        settings.autoRetry && outcome.error.isRecoverable && entity.retryCount < settings.retryAttempts
                    if (recoverable) {
                        store.incrementRetry(id)
                        store.updateStatus(id, DownloadStatus.QUEUED, outcome.error.name, outcome.message)
                    } else {
                        store.updateStatus(id, DownloadStatus.FAILED, outcome.error.name, outcome.message)
                    }
                }
            }
        }

        private suspend fun markCanceled(entity: DownloadEntity) {
            storage.cleanup(entity.id)
            store.deleteSegments(entity.id)
            store.updateStatus(entity.id, DownloadStatus.CANCELED, DownloadError.CANCELED.name, null)
        }

        private fun resolveSegments(
            entity: DownloadEntity,
            totalBytes: Long,
            acceptRanges: Boolean,
            settings: EngineSettings,
            segmentCapFor: (String) -> Int,
        ): Int {
            if (!acceptRanges || totalBytes < SegmentPlanner.AUTO_THRESHOLD) return 1
            val requested = entity.segmentsPlanned
            val auto = requested <= 0
            val base =
                if (auto) {
                    if (settings.autoDetectSegments) settings.maxSegmentsPerFile else 1
                } else {
                    requested
                }
            val cap = segmentCapFor(CookieScopePolicy.hostOf(entity.finalUrl ?: entity.url))
            return base.coerceIn(1, cap)
        }

        private fun connectedLimit(limitKBps: Long): Long = if (limitKBps > 0) limitKBps * 1024L / 4L else 0L

        companion object {
            private const val BUFFER_SIZE = 128 * 1024
            private const val FLUSH_INTERVAL_MS = 500L
            private const val AGGREGATE_PROGRESS_INTERVAL_MS = 500L
            private const val SPACE_CHECK_INTERVAL_MS = 2_000L
            private const val MAX_REDIRECTS = 10
            private const val TORRENT_POLL_MS = 500L
            private const val TORRENT_METADATA_TIMEOUT_MS = 180_000L
            const val DEFAULT_USER_AGENT: String =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 ArcDM/1.0"
        }
    }

private object CookieJarNoop : okhttp3.CookieJar {
    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = emptyList()

    override fun saveFromResponse(
        url: okhttp3.HttpUrl,
        cookies: List<okhttp3.Cookie>,
    ) = Unit
}
