package com.arc.downloadmanager.ui.downloads

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.db.SegmentEntity
import com.arc.downloadmanager.data.repository.DownloadRepository
import com.arc.downloadmanager.engine.store.DownloadStore
import com.arc.downloadmanager.util.SpeedTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: DownloadRepository,
        store: DownloadStore,
    ) : ViewModel() {
        private val id: String = savedStateHandle.get<String>("id") ?: ""
        private val speedTracker = SpeedTracker()

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
            ) { entity, segments, _ ->
                // Live smoothed speed while the task moves; drops history when terminal.
                val speed =
                    if (entity != null && entity.status.isActive) {
                        speedTracker.record(entity.id, entity.downloadedBytes)
                    } else {
                        speedTracker.forget(entity?.id ?: "")
                        0L
                    }
                DetailUiState(
                    entity = entity,
                    segments = segments,
                    speedBps = speed,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

        fun pause() = repository.pause(id)

        fun resume() = repository.resume(id)

        fun cancel() = repository.cancel(id)

        fun retry() = repository.retry(id)

        fun restart() = repository.restart(id)

        fun archive() = repository.archive(listOf(id), archived = true)

        fun restore() = repository.archive(listOf(id), archived = false)

        fun delete(
            deleteFile: Boolean,
            onDone: () -> Unit,
        ) {
            viewModelScope.launch {
                repository.delete(id, deleteFile = deleteFile)
                onDone()
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
    }

data class DetailUiState(
    val entity: DownloadEntity? = null,
    val segments: List<SegmentEntity> = emptyList(),
    /** Live smoothed speed in bytes/sec (0 when not downloading). */
    val speedBps: Long = 0L,
)
