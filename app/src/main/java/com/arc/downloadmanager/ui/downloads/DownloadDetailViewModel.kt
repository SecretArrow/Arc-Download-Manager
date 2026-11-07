package com.arc.downloadmanager.ui.downloads

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arc.downloadmanager.R
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.db.SegmentEntity
import com.arc.downloadmanager.data.repository.DownloadRepository
import com.arc.downloadmanager.engine.Protocols
import com.arc.downloadmanager.engine.store.DownloadStore
import com.arc.downloadmanager.engine.torrent.TorrentEngine
import com.arc.downloadmanager.engine.torrent.TorrentFileInfo
import com.arc.downloadmanager.engine.torrent.TorrentRunState
import com.arc.downloadmanager.engine.torrent.TorrentSnapshot
import com.arc.downloadmanager.ui.components.PendingAction
import com.arc.downloadmanager.ui.components.PendingActionPolicy
import com.arc.downloadmanager.ui.components.TaskActions
import com.arc.downloadmanager.util.SpeedTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: DownloadRepository,
        store: DownloadStore,
        private val torrentEngine: TorrentEngine,
        private val vaultRepository: com.arc.downloadmanager.data.vault.VaultRepository,
    ) : ViewModel() {
        private val id: String = savedStateHandle.get<String>("id") ?: ""
        private val speedTracker = SpeedTracker()

        /** Action clicked but not yet reflected by the engine (button spinner). */
        private val pendingAction = MutableStateFlow<PendingAction?>(null)

        /** "Save to Vault" copy/encrypt in progress. */
        private val vaultSaving = MutableStateFlow(false)

        /** Delete (task + optional file) in progress. */
        private val deleting = MutableStateFlow(false)

        val detail: StateFlow<DetailUiState> =
            combine(
                repository.observeById(id),
                store.observeSegments(id),
                flow {
                    while (true) {
                        emit(System.currentTimeMillis())
                        delay(1_000)
                    }
                },
                pendingAction,
                combine(vaultSaving, deleting) { saving, deleting -> saving to deleting },
            ) { entity, segments, _, pending, (saving, deleting) ->
                // Live smoothed speed while the task moves; drops history when terminal.
                val speed =
                    if (entity != null && entity.status.isActive) {
                        speedTracker.record(entity.id, entity.downloadedBytes)
                    } else {
                        speedTracker.forget(entity?.id ?: "")
                        0L
                    }
                // Live BitTorrent stats (peers/seeds/state) while a magnet task is active.
                val torrent =
                    if (entity != null && entity.status.isActive && Protocols.schemeOf(entity.url) == Protocols.MAGNET) {
                        torrentEngine.snapshot(entity.url)
                    } else {
                        null
                    }
                // Resolve the pending click once the status moves (or it times out).
                val liveAction =
                    entity?.let { e ->
                        pending?.takeIf { PendingActionPolicy.isActive(it, e.status, System.currentTimeMillis()) }
                    }?.action
                DetailUiState(
                    entity = entity,
                    segments = segments,
                    speedBps = speed,
                    torrent = torrent,
                    pendingAction = liveAction,
                    vaultSaving = saving,
                    deleting = deleting,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

        /** Per-file metadata for magnet tasks; empty until the torrent's metadata arrives. */
        private val _torrentFiles = MutableStateFlow<List<TorrentFileInfo>>(emptyList())

        /** Per-file selection state for magnet tasks (index, path, size, 0 = excluded). */
        val torrentFiles: StateFlow<List<TorrentFileInfo>> = _torrentFiles.asStateFlow()

        private var torrentFilesJob: Job? = null

        init {
            // Poll torrent file metadata every 2 s while the detail screen shows a magnet
            // task. Restarted only when the URL or the active/terminal state changes, so a
            // progress write does not reset the poll cadence. Cancelled in onCleared.
            viewModelScope.launch {
                repository.observeById(id)
                    .map { entity -> entity?.url to (entity?.status?.isActive == true) }
                    .distinctUntilChanged()
                    .collect { (url, active) ->
                        torrentFilesJob?.cancel()
                        torrentFilesJob = null
                        if (url != null && url.startsWith(MAGNET_PREFIX)) {
                            torrentFilesJob = launch { pollTorrentFiles(url, active) }
                        } else {
                            _torrentFiles.value = emptyList()
                        }
                    }
            }
        }

        override fun onCleared() {
            torrentFilesJob?.cancel()
            super.onCleared()
        }

        fun pause() {
            recordAction(TaskActions.PAUSE)
            repository.pause(id)
        }

        fun resume() {
            recordAction(TaskActions.RESUME)
            repository.resume(id)
        }

        fun cancel() {
            recordAction(TaskActions.CANCEL)
            repository.cancel(id)
        }

        fun retry() {
            recordAction(TaskActions.RETRY)
            repository.retry(id)
        }

        fun restart() {
            recordAction(TaskActions.RESTART)
            repository.restart(id)
        }

        /** Snapshots the current status at click time for the in-flight spinner. */
        private fun recordAction(action: String) {
            val entity = detail.value.entity ?: return
            pendingAction.value = PendingAction(action, entity.status, System.currentTimeMillis())
        }

        fun archive() = repository.archive(listOf(id), archived = true)

        /** "Save to Vault" for a completed download; navigates when locked/unset.
         *  Multi-file torrents store several URIs (newline-joined); all of them are vaulted. */
        fun saveToVault(
            entity: DownloadEntity,
            onNavigate: () -> Unit,
            onResult: (res: Int, arg: String?) -> Unit,
        ) {
            val uris = com.arc.downloadmanager.util.ShareUris.split(entity.finalUri)
            if (uris.isEmpty()) return onResult(R.string.vault_import_failed, null)
            if (vaultSaving.value) return
            vaultSaving.value = true
            viewModelScope.launch {
                try {
                    var saved = 0
                    var firstName: String? = null
                    for (uri in uris) {
                        when (
                            val r =
                                vaultRepository.saveFromExternal(
                                    uri,
                                    entity.filename,
                                    entity.mimeType,
                                )
                        ) {
                            is com.arc.downloadmanager.data.vault.VaultRepository.ExternalSaveResult.Done -> {
                                saved++
                                if (firstName == null) firstName = r.name
                            }
                            com.arc.downloadmanager.data.vault.VaultRepository.ExternalSaveResult.Failed -> Unit
                            com.arc.downloadmanager.data.vault.VaultRepository.ExternalSaveResult.NeedsSetup,
                            com.arc.downloadmanager.data.vault.VaultRepository.ExternalSaveResult.NeedsUnlock,
                            -> {
                                onNavigate()
                                return@launch
                            }
                        }
                    }
                    if (saved == 0 || firstName == null) {
                        onResult(R.string.vault_import_failed, null)
                    } else {
                        val label = if (saved > 1) "$firstName (+${saved - 1})" else firstName
                        onResult(R.string.vault_saved_to_vault, label)
                    }
                } finally {
                    vaultSaving.value = false
                }
            }
        }

        fun restore() = repository.archive(listOf(id), archived = false)

        fun delete(
            deleteFile: Boolean,
            onDone: () -> Unit,
        ) {
            if (deleting.value) return
            deleting.value = true
            viewModelScope.launch {
                try {
                    repository.delete(id, deleteFile = deleteFile)
                    onDone()
                } finally {
                    deleting.value = false
                }
            }
        }

        fun saveEdit(
            url: String,
            filename: String,
            onResult: (Boolean) -> Unit,
        ) {
            viewModelScope.launch {
                onResult(repository.setTaskMeta(id, url, filename))
            }
        }

        /** Includes/excludes a torrent file: applies it to the engine, then updates the UI optimistically. */
        fun setTorrentFileIncluded(
            index: Int,
            include: Boolean,
        ) {
            val url = detail.value.entity?.url ?: return
            if (!url.startsWith(MAGNET_PREFIX)) return
            torrentEngine.setFilePriority(url, index, include)
            _torrentFiles.update { files ->
                files.map { file ->
                    if (file.fileIndex == index) file.copy(priority = if (include) 1 else 0) else file
                }
            }
        }

        /**
         * Polls per-file metadata every 2 s while the task is active; when the task is
         * terminal it emits the current list once (a paused/finished handle may still be
         * in the session and expose its files).
         */
        private suspend fun pollTorrentFiles(
            url: String,
            active: Boolean,
        ) {
            while (true) {
                val snapshot = torrentEngine.snapshot(url)
                _torrentFiles.value =
                    if (snapshot != null && snapshot.state != TorrentRunState.FETCHING_METADATA) {
                        torrentEngine.files(url)
                    } else {
                        emptyList()
                    }
                if (!active) break
                delay(TORRENT_FILE_POLL_MS)
            }
        }

        private companion object {
            const val MAGNET_PREFIX = "magnet:"
            const val TORRENT_FILE_POLL_MS = 2_000L
        }
    }

data class DetailUiState(
    val entity: DownloadEntity? = null,
    val segments: List<SegmentEntity> = emptyList(),
    /** Live smoothed speed in bytes/sec (0 when not downloading). */
    val speedBps: Long = 0L,
    /** Live BitTorrent stats for magnet tasks (null otherwise). */
    val torrent: TorrentSnapshot? = null,
    /** Action clicked but not yet applied by the engine (button spinner). */
    val pendingAction: String? = null,
    /** "Save to Vault" copy/encrypt in progress. */
    val vaultSaving: Boolean = false,
    /** Delete in progress. */
    val deleting: Boolean = false,
)
