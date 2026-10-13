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
import com.arc.downloadmanager.engine.StorageManager
import com.arc.downloadmanager.util.SpeedTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val repository: DownloadRepository,
        private val settingsRepository: SettingsRepository,
        private val storageManager: StorageManager,
    ) : ViewModel() {
        private val speedTracker = SpeedTracker()

        val downloads: StateFlow<List<DownloadEntity>> =
            repository.observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        /** Ticks once per second so speed/ETA cells refresh without spamming the DB. */
        private val ticker =
            flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(1_000)
                }
            }

        // List-shaping controls (kept as flows so every change recomposes the list).
        val query = MutableStateFlow("")
        val filter = MutableStateFlow(HomeFilter.ALL)
        val sort = MutableStateFlow(HomeSort.DATE_NEWEST)
        val selection = MutableStateFlow<Set<String>>(emptySet())

        private val controls =
            combine(query, filter, sort, selection) { q, f, s, sel ->
                Controls(q, f, s, sel)
            }

        private data class Controls(
            val query: String,
            val filter: HomeFilter,
            val sort: HomeSort,
            val selection: Set<String>,
        )

        val ui: StateFlow<HomeUiState> =
            combine(repository.observeAll(), ticker, controls) { list, _, controls ->
                val visible = HomeListFilter.apply(list, controls.query, controls.filter, controls.sort)
                val active = list.filter { it.status.isActive && it.archivedAt == null }
                val speeds = mutableMapOf<String, Long>()
                val liveIds = active.map { it.id }.toSet()
                // Drop trackers for finished/removed tasks so their history cannot leak in.
                (speedTracker.ids() - liveIds).forEach { speedTracker.forget(it) }
                active.forEach { entity -> speeds[entity.id] = speedTracker.record(entity.id, entity.downloadedBytes) }
                HomeUiState(
                    downloads = list,
                    visibleDownloads = visible,
                    query = controls.query,
                    filter = controls.filter,
                    sort = controls.sort,
                    selection = controls.selection,
                    activeCount = active.size,
                    runningCount = list.count { it.archivedAt == null && (it.status.isActive || it.status.isRunningCandidate) },
                    completedCount = list.count { it.archivedAt == null && it.status == com.arc.downloadmanager.domain.model.DownloadStatus.COMPLETED },
                    archivedCount = list.count { it.archivedAt != null },
                    failedCount =
                        list.count {
                            it.archivedAt == null &&
                                (
                                    it.status == com.arc.downloadmanager.domain.model.DownloadStatus.FAILED ||
                                        it.status == com.arc.downloadmanager.domain.model.DownloadStatus.AUTH_REQUIRED
                                )
                        },
                    attention =
                        list.filter {
                            it.archivedAt == null &&
                                (
                                    it.status == com.arc.downloadmanager.domain.model.DownloadStatus.FAILED ||
                                        it.status == com.arc.downloadmanager.domain.model.DownloadStatus.AUTH_REQUIRED
                                )
                        },
                    recentCompleted =
                        list.filter {
                            it.archivedAt == null && it.status == com.arc.downloadmanager.domain.model.DownloadStatus.COMPLETED
                        }.sortedByDescending { it.completedAt ?: 0 }.take(5),
                    speeds = speeds,
                    totalSpeed = active.sumOf { speeds[it.id] ?: 0L },
                    storageFree = storageManager.freeBytes(),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

        val settings =
            settingsRepository.settings
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

        fun resolveDuplicate(
            request: DownloadRequest,
            policy: OverwritePolicy,
        ) {
            viewModelScope.launch {
                repository.add(request, policyOverride = policy)
                pendingDuplicate.value = null
            }
        }

        fun dismissDuplicate() {
            pendingDuplicate.value = null
        }

        fun addBatch(
            requests: List<DownloadRequest>,
            onResult: (BatchResult) -> Unit,
        ) {
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

        // List shaping ---------------------------------------------------------

        fun setQuery(value: String) {
            query.value = value
        }

        fun setFilter(value: HomeFilter) {
            filter.value = value
        }

        fun setSort(value: HomeSort) {
            sort.value = value
        }

        // Selection ------------------------------------------------------------

        fun toggleSelected(id: String) {
            selection.value = selection.value.toMutableSet().apply { if (!add(id)) remove(id) }
        }

        fun selectAllVisible() {
            selection.value = ui.value.visibleDownloads.map { it.id }.toSet()
        }

        fun clearSelection() {
            selection.value = emptySet()
        }

        // Task management ------------------------------------------------------

        fun archiveSelected() {
            val ids = selection.value.toList()
            repository.archive(ids, archived = true)
            clearSelection()
        }

        fun restoreSelected() {
            val ids = selection.value.toList()
            repository.archive(ids, archived = false)
            clearSelection()
        }

        fun archive(id: String) = repository.archive(listOf(id), archived = true)

        fun restore(id: String) = repository.archive(listOf(id), archived = false)

        fun deleteSelected(deleteFiles: Boolean) {
            val ids = selection.value.toList()
            viewModelScope.launch {
                repository.deleteMany(ids, deleteFiles)
                clearSelection()
            }
        }

        fun clearFinished(onCleared: (Int) -> Unit) {
            viewModelScope.launch {
                onCleared(repository.clearFinished())
            }
        }

        fun saveEdit(
            id: String,
            url: String,
            filename: String,
            onDone: (Boolean) -> Unit,
        ) {
            viewModelScope.launch {
                onDone(repository.setTaskMeta(id, url, filename))
            }
        }
    }

data class HomeUiState(
    val downloads: List<DownloadEntity> = emptyList(),
    /** Filtered + searched + sorted slice rendered as the main list. */
    val visibleDownloads: List<DownloadEntity> = emptyList(),
    val query: String = "",
    val filter: HomeFilter = HomeFilter.ALL,
    val sort: HomeSort = HomeSort.DATE_NEWEST,
    val selection: Set<String> = emptySet(),
    val activeCount: Int = 0,
    val runningCount: Int = 0,
    val failedCount: Int = 0,
    val completedCount: Int = 0,
    val archivedCount: Int = 0,
    val attention: List<DownloadEntity> = emptyList(),
    val recentCompleted: List<DownloadEntity> = emptyList(),
    /** Live per-task speed in bytes/sec, keyed by task id. */
    val speeds: Map<String, Long> = emptyMap(),
    val totalSpeed: Long = 0L,
    val storageFree: Long = 0L,
) {
    val activeBytes: Long
        get() = downloads.filter { it.status.isActive }.sumOf { it.downloadedBytes }

    val activeTotalBytes: Long
        get() = downloads.filter { it.status.isActive }.sumOf { it.totalBytes.coerceAtLeast(0) }

    val inSelectionMode: Boolean
        get() = selection.isNotEmpty()
}
