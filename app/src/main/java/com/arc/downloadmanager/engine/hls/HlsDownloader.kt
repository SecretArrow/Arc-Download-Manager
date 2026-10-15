package com.arc.downloadmanager.engine.hls

import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.BandwidthLimiter
import com.arc.downloadmanager.engine.DownloadError
import com.arc.downloadmanager.engine.MergeManager
import com.arc.downloadmanager.engine.PauseReason
import com.arc.downloadmanager.engine.TaskControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Terminal outcome of [HlsDownloader.download]. Mapped 1:1 onto TaskRunner's TaskOutcome. */
sealed interface HlsResult {
    /** Segments merged (init segment included) into [mergedFile]; [extension] is "ts" or "mp4". */
    data class Completed(
        val mergedFile: File,
        val extension: String,
        val totalBytes: Long,
    ) : HlsResult

    data class Paused(
        val reason: PauseReason,
    ) : HlsResult

    data object Canceled : HlsResult

    data class Failed(
        val error: DownloadError,
        val message: String?,
    ) : HlsResult
}

/**
 * HLS/M3U8 transfer pipeline: playlist fetch (+ master variant selection), optional AES-128
 * segment decryption, limited-parallelism segment download into the task temp dir and an
 * ordered byte-concat merge. Pure JVM (OkHttp + coroutines) — no Android framework imports.
 *
 * Resume model: finished segments live in [download]'s [tempDir] as zero-padded
 * `NNNNNN.part` files and are skipped on re-run; in-flight segments are downloaded to a
 * `.tmp` sibling and renamed on completion, so a partial segment is never mistaken for a
 * finished one (CBC data is not resumable mid-file anyway).
 *
 * Progress model: [downloadedBytes] accumulates per finished (or resumed) segment;
 * [totalBytes] is a stable estimate (average finished segment size × slot count) that becomes
 * exact once the last segment completes; DB writes are throttled to ≤1/s or per segment.
 */
class HlsDownloader(
    private val client: OkHttpClient,
    /** Returns a Request.Builder pre-loaded with the task's UA/referer/cookies (no URL set). */
    private val headerFactory: () -> okhttp3.Request.Builder,
) {
    /**
     * Runs the full HLS download.
     *
     * @param playlistUrl absolute URL of the (master or media) playlist.
     * @param tempDir per-task staging directory for segments and the merged output.
     * @param onStatus status transitions: CONNECTING (playlist) → DOWNLOADING → MERGING.
     * @param onPlanned invoked once with the media-segment count (persisted as segmentsPlanned).
     * @param onProgress throttled progress writes (downloadedBytes, estimated totalBytes).
     * @param isOnline liveness probe checked between segments and on the read loop.
     * @param hasFreeSpace storage probe checked before the segment phase.
     * @param limiter optional shared per-connection bandwidth limiter applied to segment reads
     *   (TaskRunner's BandwidthLimiter is a synchronized token bucket, so one instance can be
     *   shared by all segment coroutines).
     */
    suspend fun download(
        playlistUrl: String,
        tempDir: File,
        control: TaskControl,
        onStatus: suspend (DownloadStatus) -> Unit = {},
        onPlanned: suspend (Int) -> Unit = {},
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        isOnline: () -> Boolean = { true },
        hasFreeSpace: suspend (Long) -> Boolean = { true },
        limiter: BandwidthLimiter? = null,
    ): HlsResult {
        tempDir.mkdirs()
        if (control.cancelRequested) return HlsResult.Canceled
        onStatus(DownloadStatus.CONNECTING)

        // 1. Playlist phase: fetch, resolve, drill down from a master playlist when needed.
        val first =
            when (val fetched = fetchText(playlistUrl)) {
                is Fetch.Text -> fetched
                else -> return failedFetch(fetched, "Playlist fetch failed")
            }
        val playlist =
            when (val outcome = parseSafely(first.body, first.effectiveUrl)) {
                is ParseOutcome.Bad ->
                    // The URL looked like HLS (extension/type/signature) but served junk.
                    return HlsResult.Failed(DownloadError.PAGE_NOT_FILE, "The .m3u8 URL did not return a valid HLS playlist (${outcome.message})")
                is ParseOutcome.Ok -> outcome.playlist
            }
        val media =
            when (playlist) {
                is HlsPlaylist.Media -> playlist
                is HlsPlaylist.Master -> {
                    // Highest bandwidth wins; maxByOrNull keeps the FIRST variant on ties.
                    val variant =
                        playlist.variants.maxByOrNull { it.bandwidth }
                            ?: return HlsResult.Failed(DownloadError.HLS_SEGMENT, "Master playlist contains no variants")
                    val variantText =
                        when (val variantFetch = fetchText(variant.url)) {
                            is Fetch.Text -> variantFetch
                            else -> return failedFetch(variantFetch, "Variant playlist fetch failed")
                        }
                    when (val outcome = parseSafely(variantText.body, variantText.effectiveUrl)) {
                        is ParseOutcome.Bad ->
                            return HlsResult.Failed(DownloadError.PAGE_NOT_FILE, "The variant playlist could not be parsed (${outcome.message})")
                        is ParseOutcome.Ok ->
                            when (val inner = outcome.playlist) {
                                is HlsPlaylist.Media -> inner
                                is HlsPlaylist.Master ->
                                    return HlsResult.Failed(DownloadError.HLS_UNSUPPORTED, "Nested master playlists are not supported")
                            }
                    }
                }
            }
        if (media.isLive) {
            return HlsResult.Failed(DownloadError.HLS_LIVE, "Live stream (no #EXT-X-ENDLIST) cannot be downloaded")
        }
        if (media.segments.isEmpty()) {
            return HlsResult.Failed(DownloadError.HLS_SEGMENT, "Playlist contains no media segments")
        }

        // 2. Segment phase.
        onStatus(DownloadStatus.DOWNLOADING)
        onPlanned(media.segments.size)
        if (!hasFreeSpace(STORAGE_HEADROOM_BYTES)) return HlsResult.Paused(PauseReason.LOW_STORAGE)

        val key =
            when (val keyResolution = resolveKey(media)) {
                is KeyResolution.Aes128 -> keyResolution.key
                KeyResolution.None -> null
                is KeyResolution.Unsupported ->
                    return HlsResult.Failed(DownloadError.HLS_UNSUPPORTED, "Unsupported HLS encryption METHOD=${keyResolution.method}")
                is KeyResolution.Failed -> return HlsResult.Failed(keyResolution.error, keyResolution.message)
            }

        // 3. fMP4 init segment first, when present.
        val hasInit = media.initSegmentUrl != null
        val initFile = File(tempDir, INIT_FILE)
        val progress = HlsProgress(media.segments.size + if (hasInit) 1 else 0, onProgress)
        if (hasInit) {
            // Without an explicit IV the init segment uses the first segment's derived IV.
            val initIv = media.encryption?.ivHex ?: HlsKeyInfo.derivedIvHex(media.segments.first().sequence)
            val initDecryptor = key?.let { KeyDecryptor(it, initIv) }
            val initOutcome =
                downloadSegmentFile(media.initSegmentUrl!!, initFile, initDecryptor, control, isOnline, limiter) {
                    progress.partial(0, it)
                }
            when (initOutcome) {
                is SegmentOutcome.Completed -> progress.finished(0, initOutcome.bytes)
                is SegmentOutcome.Skipped -> progress.finished(0, initOutcome.bytes)
                is SegmentOutcome.Paused -> return pausedWithProgress(progress, initOutcome.reason)
                SegmentOutcome.Canceled -> return HlsResult.Canceled
                is SegmentOutcome.Failed -> return HlsResult.Failed(initOutcome.error, initOutcome.message)
            }
        }

        // 4. Media segments with limited parallelism (3), concatenated in order afterwards.
        val segmentOutcomes =
            coroutineScope {
                val permits = Semaphore(SEGMENT_PARALLELISM)
                media.segments.mapIndexed { index, segment ->
                    async(Dispatchers.IO) {
                        permits.withPermit {
                            val slot = index + if (hasInit) 1 else 0
                            val target = File(tempDir, String.format(Locale.US, "%06d.part", index))
                            val iv = media.encryption?.ivHex ?: HlsKeyInfo.derivedIvHex(segment.sequence)
                            val segmentDecryptor = key?.let { KeyDecryptor(it, iv) }
                            val outcome =
                                downloadSegmentFile(segment.url, target, segmentDecryptor, control, isOnline, limiter) {
                                    progress.partial(slot, it)
                                }
                            when (outcome) {
                                is SegmentOutcome.Completed -> progress.finished(slot, outcome.bytes)
                                is SegmentOutcome.Skipped -> progress.finished(slot, outcome.bytes)
                                else -> Unit
                            }
                            outcome
                        }
                    }
                }.awaitAll()
            }
        for (outcome in segmentOutcomes) {
            when (outcome) {
                is SegmentOutcome.Completed, is SegmentOutcome.Skipped -> Unit
                is SegmentOutcome.Paused -> return pausedWithProgress(progress, outcome.reason)
                SegmentOutcome.Canceled -> return HlsResult.Canceled
                is SegmentOutcome.Failed -> return HlsResult.Failed(outcome.error, outcome.message)
            }
        }
        progress.flush(force = true)

        // 5. Ordered concat: init segment (fMP4) + segments → single output file.
        onStatus(DownloadStatus.MERGING)
        val parts =
            buildList {
                if (hasInit && initFile.exists()) add(initFile)
                media.segments.indices.forEach { index -> add(File(tempDir, String.format(Locale.US, "%06d.part", index))) }
            }
        val output = File(tempDir, MERGED_FILE)
        try {
            MergeManager.merge(parts, output)
        } catch (e: IOException) {
            return HlsResult.Failed(DownloadError.MERGE_FAILED, e.message)
        }
        // Container: fMP4 streams (EXT-X-MAP present) are .mp4, MPEG-TS streams are .ts.
        val extension = if (hasInit) "mp4" else "ts"
        return HlsResult.Completed(output, extension, output.length())
    }

    // region Playlist + key fetching

    private sealed interface ParseOutcome {
        data class Ok(
            val playlist: HlsPlaylist,
        ) : ParseOutcome

        data class Bad(
            val message: String?,
        ) : ParseOutcome
    }

    private fun parseSafely(
        text: String,
        url: String,
    ): ParseOutcome =
        try {
            ParseOutcome.Ok(parse(text, url))
        } catch (e: IllegalArgumentException) {
            ParseOutcome.Bad(e.message)
        }

    private sealed interface KeyResolution {
        data object None : KeyResolution

        data class Aes128(
            val key: ByteArray,
        ) : KeyResolution

        data class Unsupported(
            val method: String,
        ) : KeyResolution

        data class Failed(
            val error: DownloadError,
            val message: String?,
        ) : KeyResolution
    }

    private suspend fun resolveKey(media: HlsPlaylist.Media): KeyResolution {
        val method = media.encryption?.method?.uppercase(Locale.US)
        return when (method) {
            null, "NONE" -> KeyResolution.None
            "AES-128" -> {
                val failure =
                    when (val fetched = fetchBytes(media.encryption.keyUrl)) {
                        is Fetch.Bytes ->
                            return if (fetched.body.size == 16) {
                                KeyResolution.Aes128(fetched.body)
                            } else {
                                KeyResolution.Failed(DownloadError.HLS_SEGMENT, "AES-128 key must be 16 bytes, got ${fetched.body.size}")
                            }
                        else -> failedFetch(fetched, "Decryption key fetch failed")
                    }
                KeyResolution.Failed(failure.error, failure.message)
            }
            else -> KeyResolution.Unsupported(method)
        }
    }

    private fun failedFetch(
        fetched: Fetch,
        what: String,
    ): HlsResult.Failed =
        when (fetched) {
            is Fetch.HttpError -> HlsResult.Failed(mapHttpError(fetched.code), "$what (HTTP ${fetched.code})")
            is Fetch.Transport -> HlsResult.Failed(DownloadError.SERVER_ERROR, "$what: ${fetched.message}")
            else -> HlsResult.Failed(DownloadError.UNKNOWN, what)
        }

    /** Mirrors TaskRunner's openStream status-code mapping. */
    private fun mapHttpError(code: Int): DownloadError =
        when {
            code == 401 || code == 403 -> DownloadError.AUTH_REQUIRED
            code == 404 || code == 410 -> DownloadError.EXPIRED_LINK
            code == 429 || code == 503 -> DownloadError.RATE_LIMITED
            else -> DownloadError.SERVER_ERROR
        }

    private suspend fun fetchText(url: String): Fetch = fetch(url, binary = false)

    private suspend fun fetchBytes(url: String): Fetch = fetch(url, binary = true)

    private suspend fun fetch(
        url: String,
        binary: Boolean,
    ): Fetch {
        val request = headerFactory().url(url).build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body
                    when {
                        !response.isSuccessful -> Fetch.HttpError(response.code)
                        body == null -> Fetch.Transport("Empty response body")
                        binary -> Fetch.Bytes(body.bytes(), response.request.url.toString())
                        else -> Fetch.Text(body.string(), response.request.url.toString())
                    }
                }
            } catch (e: IOException) {
                Fetch.Transport(e.message)
            }
        }
    }

    // endregion

    // region Segment transfer

    private suspend fun pausedWithProgress(
        progress: HlsProgress,
        reason: PauseReason,
    ): HlsResult {
        progress.flush(force = true)
        return HlsResult.Paused(reason)
    }

    /**
     * Downloads one segment to [target] (via a `.tmp` sibling that is renamed on completion),
     * retrying transient failures up to [MAX_INLINE_RETRIES] times with a 1 s / 2 s backoff
     * before the task fails with [DownloadError.HLS_SEGMENT]. [onPartial] receives the running
     * byte count for progress aggregation.
     */
    private suspend fun downloadSegmentFile(
        url: String,
        target: File,
        decryptor: KeyDecryptor?,
        control: TaskControl,
        isOnline: () -> Boolean,
        limiter: BandwidthLimiter?,
        onPartial: suspend (Long) -> Unit,
    ): SegmentOutcome {
        // Resume: a non-empty finished .part file is complete by construction (atomic rename).
        if (target.exists() && target.length() > 0) return SegmentOutcome.Skipped(target.length())
        val tmp = File(target.parentFile, target.name + ".tmp")
        var attempt = 0
        while (true) {
            if (control.cancelRequested) return SegmentOutcome.Canceled
            if (control.pauseRequested) return SegmentOutcome.Paused(PauseReason.USER)
            if (!isOnline()) return SegmentOutcome.Paused(PauseReason.NO_NETWORK)
            attempt++
            tmp.delete()
            when (val written = writeBody(url, tmp, decryptor, control, isOnline, limiter, onPartial)) {
                is WriteResult.Done -> {
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    return SegmentOutcome.Completed(target.length())
                }
                is WriteResult.Canceled -> {
                    tmp.delete()
                    return SegmentOutcome.Canceled
                }
                is WriteResult.Paused -> {
                    tmp.delete()
                    return SegmentOutcome.Paused(PauseReason.USER)
                }
                is WriteResult.NoNetwork -> {
                    tmp.delete()
                    return SegmentOutcome.Paused(PauseReason.NO_NETWORK)
                }
                is WriteResult.Error -> {
                    tmp.delete()
                    if (attempt > MAX_INLINE_RETRIES) {
                        return SegmentOutcome.Failed(DownloadError.HLS_SEGMENT, written.message)
                    }
                    // Inline backoff: 1 s after the first failure, 2 s after the second.
                    delay(INLINE_BACKOFF_MS[attempt - 1])
                }
            }
        }
    }

    /** Streams [url] into [tmp]; transport/HTTP problems surface as [WriteResult.Error]. */
    private suspend fun writeBody(
        url: String,
        tmp: File,
        decryptor: KeyDecryptor?,
        control: TaskControl,
        isOnline: () -> Boolean,
        limiter: BandwidthLimiter?,
        onPartial: suspend (Long) -> Unit,
    ): WriteResult {
        val request = headerFactory().url(url).build()
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty response body")
                    var input: InputStream = body.byteStream()
                    if (decryptor != null) input = decryptor.wrap(input)
                    input.use { stream ->
                        FileOutputStream(tmp).use { out ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var written = 0L
                            var lastCheck = System.currentTimeMillis()
                            while (true) {
                                if (control.cancelRequested) return@withContext WriteResult.Canceled
                                if (control.pauseRequested) return@withContext WriteResult.Paused
                                val read = stream.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                written += read
                                limiter?.onBytes(read)
                                val now = System.currentTimeMillis()
                                if (now - lastCheck >= FLUSH_INTERVAL_MS) {
                                    lastCheck = now
                                    onPartial(written)
                                    if (!isOnline()) return@withContext WriteResult.NoNetwork
                                }
                            }
                            WriteResult.Done
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // BadPaddingException on truncated/corrupt AES data surfaces as an IOException here.
            WriteResult.Error(e.message ?: "segment transfer failed")
        }
    }

    // endregion

    private sealed interface Fetch {
        data class Text(
            val body: String,
            val effectiveUrl: String,
        ) : Fetch

        data class Bytes(
            val body: ByteArray,
            val effectiveUrl: String,
        ) : Fetch

        data class HttpError(
            val code: Int,
        ) : Fetch

        data class Transport(
            val message: String?,
        ) : Fetch
    }

    /** AES-128-CBC decryptor with PKCS#7 padding; a fresh cipher is built per stream. */
    private class KeyDecryptor(
        private val key: ByteArray,
        private val ivHex: String,
    ) {
        fun wrap(input: InputStream): InputStream {
            // JCE's PKCS5Padding is the 16-byte-block PKCS#7 padding for AES.
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivHex.hexToBytes()))
            return CipherInputStream(input, cipher)
        }
    }

    private sealed interface SegmentOutcome {
        data class Completed(
            val bytes: Long,
        ) : SegmentOutcome

        data class Skipped(
            val bytes: Long,
        ) : SegmentOutcome

        data class Paused(
            val reason: PauseReason,
        ) : SegmentOutcome

        data object Canceled : SegmentOutcome

        data class Failed(
            val error: DownloadError,
            val message: String?,
        ) : SegmentOutcome
    }

    private sealed interface WriteResult {
        data object Done : WriteResult

        data object Canceled : WriteResult

        data object Paused : WriteResult

        data object NoNetwork : WriteResult

        data class Error(
            val message: String?,
        ) : WriteResult
    }

    /**
     * Aggregates per-slot byte counts (slot 0 = init segment when present) into throttled
     * progress writes. The total is a stable estimate: average finished segment size × slot
     * count — exact once every slot is finished.
     */
    private class HlsProgress(
        private val slotCount: Int,
        private val write: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        private val bytes = AtomicLongArray(slotCount)
        private val finished = AtomicInteger(0)
        private val mutex = Mutex()
        private var lastWrite = 0L

        suspend fun partial(
            slot: Int,
            segmentBytes: Long,
        ) {
            bytes.set(slot, segmentBytes)
            flush(force = false)
        }

        suspend fun finished(
            slot: Int,
            segmentBytes: Long,
        ) {
            bytes.set(slot, segmentBytes)
            finished.incrementAndGet()
            flush(force = true) // per-segment write cadence (spec: ≤1/s or per segment)
        }

        suspend fun flush(force: Boolean) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                if (!force && now - lastWrite < FLUSH_INTERVAL_MS) return
                lastWrite = now
                var done = 0L
                for (i in 0 until slotCount) done += bytes.get(i)
                val doneCount = finished.get()
                val estimate = if (doneCount > 0) done / doneCount * slotCount else -1L
                write(done, estimate)
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 128 * 1024
        private const val FLUSH_INTERVAL_MS = 500L
        private const val SEGMENT_PARALLELISM = 3
        private const val MAX_INLINE_RETRIES = 2
        private val INLINE_BACKOFF_MS = longArrayOf(1_000L, 2_000L)
        private const val INIT_FILE = "00000-init"
        private const val MERGED_FILE = "hls-merged.bin"
        private const val STORAGE_HEADROOM_BYTES = 64L * 1024L * 1024L
    }
}

/** Decodes a hex string (optional 0x prefix) into bytes. */
private fun String.hexToBytes(): ByteArray {
    val clean = trim().removePrefix("0x").removePrefix("0X").replace(" ", "")
    require(clean.length % 2 == 0 && clean.isNotEmpty()) { "Invalid IV hex: $this" }
    return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
