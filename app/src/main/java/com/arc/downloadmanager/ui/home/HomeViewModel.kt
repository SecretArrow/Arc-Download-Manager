package com.arc.downloadmanager.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.repository.AddResult
import com.arc.downloadmanager.data.repository.BatchResult
import com.arc.downloadmanager.data.repository.DownloadRepository
import com.arc.downloadmanager.data.settings.OverwritePolicy
import com.arc.downloadmanager.data.settings.SettingsRepository
import com.arc.downloadmanager.domain.model.ConflictAction
import com.arc.downloadmanager.domain.model.DownloadRequest
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.StorageManager
import com.arc.downloadmanager.ui.components.ConflictPromptPolicy
import com.arc.downloadmanager.ui.components.PendingAction
import com.arc.downloadmanager.ui.components.PendingActionPolicy
import com.arc.downloadmanager.ui.components.TaskActions
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

        /**
         * Actions clicked but not yet reflected by the engine, keyed by task id —
         * drives spinner + locked state on the affected row buttons.
         */
        private val pendingActions = MutableStateFlow<Map<String, PendingAction>>(emptyMap())

        /** Bulk top-bar action currently in flight (pause-all / resume-all). */
        private val bulkAction = MutableStateFlow<String?>(null)

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
            combine(repository.observeAll(), ticker, controls, pendingActions, bulkAction) { list, _, controls, pending, bulk ->
                val visible = HomeListFilter.apply(list, controls.query, controls.filter, controls.sort)
                val liveActions =
                    PendingActionPolicy.activeOnly(
                        pending,
                        list.associate { it.id to it.status },
                        System.currentTimeMillis(),
                    )
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
                    pendingActions = liveActions,
                    bulkAction = bulk,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

        val settings =
            settingsRepository.settings
                .stateIn(viewModelScope, SharingStarted.Eagerly, com.arc.downloadmanager.data.settings.EngineSettings())

        /** A duplicate was detected while adding: UI must ask the user what to do. */
        val pendingDuplicate = MutableStateFlow<DownloadRequest?>(null)

        /**
         * A finished download hit a filename collision at the destination and is parked in
         * CONFLICT: the dialog asks the user to overwrite / rename new / rename old. One at a time.
         */
        val activeConflict = MutableStateFlow<DownloadEntity?>(null)

        /** Conflicts postponed or resolved in this session; they do not re-prompt automatically. */
        private val resolvedConflicts = MutableStateFlow<Set<String>>(emptySet())

        init {
            viewModelScope.launch {
                downloads.collect { list ->
                    val next =
                        ConflictPromptPolicy.next(
                            list.associate { it.id to it.status },
                            resolvedConflicts.value,
                        )
                    activeConflict.value = next?.let { id -> list.firstOrNull { it.id == id } }
                }
            }
        }

        /** Re-opens the conflict dialog for a task the user postponed (row pencil action). */
        fun openConflict(id: String) {
            resolvedConflicts.value = resolvedConflicts.value - id
            activeConflict.value = downloads.value.firstOrNull { it.id == id }
        }

        fun dismissConflict(id: String) {
            resolvedConflicts.value = resolvedConflicts.value + id
            activeConflict.value = null
        }

        fun resolveConflict(
            id: String,
            action: ConflictAction,
        ) {
            resolvedConflicts.value = resolvedConflicts.value + id
            activeConflict.value = null
            recordAction(id, TaskActions.RESOLVE)
            repository.resolveConflict(id, action)
        }

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

        fun pause(id: String) {
            recordAction(id, TaskActions.PAUSE)
            repository.pause(id)
        }

        fun resume(id: String) {
            recordAction(id, TaskActions.RESUME)
            repository.resume(id)
        }

        fun cancel(id: String) {
            recordAction(id, TaskActions.CANCEL)
            repository.cancel(id)
        }

        fun retry(id: String) {
            recordAction(id, TaskActions.RETRY)
            repository.retry(id)
        }

        fun restart(id: String) {
            recordAction(id, TaskActions.RESTART)
            repository.restart(id)
        }

        fun pauseAll() {
            val now = System.currentTimeMillis()
            bulkAction.value = TaskActions.PAUSE_ALL
            val targets =
                downloads.value.filter {
                    it.archivedAt == null && (it.status.isActive || it.status.isRunningCandidate)
                }
            pendingActions.value =
                pendingActions.value +
                targets.associate { it.id to PendingAction(TaskActions.PAUSE, it.status, now) }
            repository.pauseAll()
            clearBulkAfterDelay()
        }

        fun resumeAll() {
            val now = System.currentTimeMillis()
            bulkAction.value = TaskActions.RESUME_ALL
            val targets =
                downloads.value.filter {
                    it.archivedAt == null &&
                        (
                            it.status == DownloadStatus.PAUSED || it.status.isRunningCandidate ||
                                it.status == DownloadStatus.FAILED ||
                                it.status == DownloadStatus.AUTH_REQUIRED ||
                                it.status == DownloadStatus.CANCELED
                        )
                }
            pendingActions.value =
                pendingActions.value +
                targets.associate { it.id to PendingAction(TaskActions.RESUME, it.status, now) }
            repository.resumeAll()
            clearBulkAfterDelay()
        }

        /** Records a click so the affected button spins until the engine reflects it. */
        private fun recordAction(
            id: String,
            action: String,
        ) {
            val entity = downloads.value.firstOrNull { it.id == id } ?: return
            pendingActions.value =
                pendingActions.value + (id to PendingAction(action, entity.status, System.currentTimeMillis()))
        }

        /** The top-bar bulk button only needs a short anti-double-click window. */
        private fun clearBulkAfterDelay() {
            viewModelScope.launch {
                delay(BULK_BUSY_MS)
                bulkAction.value = null
            }
        }

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

        private companion object {
            const val BULK_BUSY_MS = 1_500L
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
    /** Clicked-but-not-yet-applied actions, keyed by task id (button spinners). */
    val pendingActions: Map<String, com.arc.downloadmanager.ui.components.PendingAction> = emptyMap(),
    /** Bulk pause-all/resume-all currently in flight (locks the top-bar buttons). */
    val bulkAction: String? = null,
) {
    val activeBytes: Long
        get() = downloads.filter { it.status.isActive }.sumOf { it.downloadedBytes }

    val activeTotalBytes: Long
        get() = downloads.filter { it.status.isActive }.sumOf { it.totalBytes.coerceAtLeast(0) }

    val inSelectionMode: Boolean
        get() = selection.isNotEmpty()
}
