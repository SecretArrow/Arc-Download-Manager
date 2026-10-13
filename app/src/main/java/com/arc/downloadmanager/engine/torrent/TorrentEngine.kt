package com.arc.downloadmanager.engine.torrent

import com.arc.downloadmanager.engine.MagnetUri
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentStatus
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Protocol-agnostic snapshot of a torrent's live state. */
data class TorrentSnapshot(
    val state: TorrentRunState,
    val name: String?,
    val totalWanted: Long,
    val totalWantedDone: Long,
    val downloadPayloadRateBps: Long,
    val numPeers: Int,
    val numSeeds: Int,
    val errorMessage: String?,
)

/** High-level torrent states the UI can render. */
enum class TorrentRunState {
    FETCHING_METADATA,
    CHECKING,
    DOWNLOADING,
    FINISHED,
    ERROR,
    UNKNOWN,
}

/**
 * Thin wrapper around jlibtorrent's [SessionManager] for magnet-link downloads.
 *
 * The native session is started lazily on the first magnet task and stays alive for the
 * process lifetime; devices without the matching ABI fall back gracefully (the task fails
 * with a friendly error instead of crashing). Handles are looked up by the magnet's v1
 * info-hash; files land in a per-task directory owned by [com.arc.downloadmanager.engine.TaskStorage].
 */
@Singleton
class TorrentEngine
    @Inject
    constructor() {
        private val started = AtomicBoolean(false)
        private val nativeAvailable = AtomicBoolean(true)
        private var sessionManager: SessionManager? = null

        /** Starts the libtorrent session once. Returns false when native libs are unavailable. */
        fun ensureStarted(): Boolean {
            if (!nativeAvailable.get()) return false
            if (started.get()) return sessionManager?.isRunning() == true
            return synchronized(this) {
                if (started.get()) {
                    return@synchronized sessionManager?.isRunning() == true
                }
                val sm =
                    try {
                        SessionManager().also { it.start() }
                    } catch (t: Throwable) {
                        // UnsatisfiedLinkError (missing ABI) or any native bootstrap failure.
                        nativeAvailable.set(false)
                        null
                    }
                sessionManager = sm
                started.set(sm != null)
                sm != null
            }
        }

        /** Adds a magnet for download into [saveDir]. Idempotent when the torrent already runs. */
        fun addMagnet(
            magnetUri: String,
            saveDir: File,
        ): Boolean {
            if (!ensureStarted()) return false
            val sm = sessionManager ?: return false
            return try {
                sm.download(magnetUri, saveDir, com.frostwire.jlibtorrent.swig.torrent_flags_t())
                true
            } catch (t: Throwable) {
                false
            }
        }

        /** Live snapshot for [magnetUri], or null when the torrent is not in the session yet. */
        fun snapshot(magnetUri: String): TorrentSnapshot? {
            val sm = sessionManager ?: return null
            // v2-only (btmh) magnets have no v1 hash to look up; libtorrent still handles
            // them, but the engine cannot observe them here - reported as "no data" upstream.
            val hashHex = MagnetUri.infoHashHex(magnetUri) ?: return null
            val handle =
                try {
                    sm.find(Sha1Hash(hashHex))
                } catch (t: Throwable) {
                    null
                } ?: return null
            return runCatching { mapSnapshot(handle) }.getOrNull()
        }

        /** Pauses the torrent's traffic (kept in session for an instant resume). */
        fun pause(magnetUri: String) {
            withHandle(magnetUri) { it.pause() }
        }

        /** Resumes a previously paused torrent. */
        fun resume(magnetUri: String) {
            withHandle(magnetUri) { it.resume() }
        }

        /** Removes the torrent from the session (files on disk are managed by the caller). */
        fun remove(magnetUri: String) {
            val sm = sessionManager ?: return
            withHandle(magnetUri) { handle -> sm.remove(handle) }
        }

        private fun withHandle(
            magnetUri: String,
            block: (TorrentHandle) -> Unit,
        ) {
            val sm = sessionManager ?: return
            val hashHex = MagnetUri.infoHashHex(magnetUri) ?: return
            val handle =
                try {
                    sm.find(Sha1Hash(hashHex))
                } catch (t: Throwable) {
                    null
                } ?: return
            runCatching { block(handle) }
        }

        private fun mapSnapshot(handle: TorrentHandle): TorrentSnapshot {
            val status: TorrentStatus = handle.status()
            val errorCode = status.errorCode()
            val errorMessage = if (errorCode.isError) errorCode.message() else null
            val mapped =
                when (status.state()) {
                    TorrentStatus.State.CHECKING_FILES,
                    TorrentStatus.State.CHECKING_RESUME_DATA,
                    -> TorrentRunState.CHECKING
                    TorrentStatus.State.DOWNLOADING_METADATA -> TorrentRunState.FETCHING_METADATA
                    TorrentStatus.State.DOWNLOADING -> TorrentRunState.DOWNLOADING
                    TorrentStatus.State.FINISHED,
                    TorrentStatus.State.SEEDING,
                    -> TorrentRunState.FINISHED
                    else -> TorrentRunState.UNKNOWN
                }
            return TorrentSnapshot(
                state = if (errorMessage != null) TorrentRunState.ERROR else mapped,
                name = status.name(),
                totalWanted = status.totalWanted(),
                totalWantedDone = status.totalWantedDone(),
                downloadPayloadRateBps = status.downloadPayloadRate().toLong(),
                numPeers = status.numPeers(),
                numSeeds = status.numSeeds(),
                errorMessage = errorMessage,
            )
        }
    }
