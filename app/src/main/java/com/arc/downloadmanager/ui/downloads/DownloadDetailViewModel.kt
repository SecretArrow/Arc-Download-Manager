package com.arc.downloadmanager.ui.downloads

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.db.SegmentEntity
import com.arc.downloadmanager.data.repository.DownloadRepository
import com.arc.downloadmanager.engine.store.DownloadStore
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
                DetailUiState(
                    entity = entity,
                    segments = segments,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

        fun pause() = repository.pause(id)

        fun resume() = repository.resume(id)

        fun cancel() = repository.cancel(id)

        fun retry() = repository.retry(id)

        fun restart() = repository.restart(id)

        fun delete(onDone: () -> Unit) {
            viewModelScope.launch {
                repository.delete(id, deleteFile = false)
                onDone()
            }
        }
    }

data class DetailUiState(
    val entity: DownloadEntity? = null,
    val segments: List<SegmentEntity> = emptyList(),
)
