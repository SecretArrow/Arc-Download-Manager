package com.arc.downloadmanager.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.repository.AddResult
import com.arc.downloadmanager.data.repository.BatchResult
import com.arc.downloadmanager.data.repository.DownloadRepository
import com.arc.downloadmanager.data.settings.OverwritePolicy
import com.arc.downloadmanager.data.settings.SettingsRepository
import com.arc.downloadmanager.domain.model.DownloadRequest
import com.arc.downloadmanager.util.SpeedTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val speedTracker = SpeedTracker()

    val downloads: StateFlow<List<DownloadEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ticks once per second so speed/ETA cells refresh without spamming the DB. */
    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    val ui: StateFlow<HomeUiState> = combine(repository.observeAll(), ticker) { list, _ ->
        val active = list.filter { it.status.isActive }
        HomeUiState(
            downloads = list,
            activeCount = active.size,
            queuedCount = list.count { it.status.isRunningCandidate },
            failedCount = list.count {
                it.status == com.arc.downloadmanager.domain.model.DownloadStatus.FAILED ||
                    it.status == com.arc.downloadmanager.domain.model.DownloadStatus.AUTH_REQUIRED
            },
            completedCount = list.count { it.status == com.arc.downloadmanager.domain.model.DownloadStatus.COMPLETED },
            attention = list.filter {
                it.status == com.arc.downloadmanager.domain.model.DownloadStatus.FAILED ||
                    it.status == com.arc.downloadmanager.domain.model.DownloadStatus.AUTH_REQUIRED
            },
            recentCompleted = list.filter { it.status == com.arc.downloadmanager.domain.model.DownloadStatus.COMPLETED }
                .sortedByDescending { it.completedAt ?: 0 }
                .take(5),
            totalSpeed = active.sumOf { speedTracker.record(it.id, it.downloadedBytes) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.arc.downloadmanager.data.settings.EngineSettings())

    /** A duplicate was detected while adding: UI must ask the user what to do. */
    val pendingDuplicate = MutableStateFlow<DownloadRequest?>(null)

    fun add(request: DownloadRequest) {
        viewModelScope.launch {
            when (val result = repository.add(request)) {
                is AddResult.Duplicate -> pendingDuplicate.value = request
                else -> Unit
            }
        }
    }

    fun resolveDuplicate(request: DownloadRequest, policy: OverwritePolicy) {
        viewModelScope.launch {
            repository.add(request, policyOverride = policy)
            pendingDuplicate.value = null
        }
    }

    fun dismissDuplicate() {
        pendingDuplicate.value = null
    }

    fun addBatch(requests: List<DownloadRequest>, onResult: (BatchResult) -> Unit) {
        viewModelScope.launch {
            val result = repository.addBatch(requests, policyOverride = OverwritePolicy.RENAME)
            onResult(result)
        }
    }

    fun pause(id: String) = repository.pause(id)

    fun resume(id: String) = repository.resume(id)

    fun cancel(id: String) = repository.cancel(id)

    fun retry(id: String) = repository.retry(id)

    fun restart(id: String) = repository.restart(id)

    fun pauseAll() = repository.pauseAll()

    fun resumeAll() = repository.resumeAll()
}

data class HomeUiState(
    val downloads: List<DownloadEntity> = emptyList(),
    val activeCount: Int = 0,
    val queuedCount: Int = 0,
    val failedCount: Int = 0,
    val completedCount: Int = 0,
    val attention: List<DownloadEntity> = emptyList(),
    val recentCompleted: List<DownloadEntity> = emptyList(),
    val totalSpeed: Long = 0L,
) {
    val activeBytes: Long
        get() = downloads.filter { it.status.isActive }.sumOf { it.downloadedBytes }

    val activeTotalBytes: Long
        get() = downloads.filter { it.status.isActive }.sumOf { it.totalBytes.coerceAtLeast(0) }
}
