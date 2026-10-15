package com.arc.downloadmanager.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arc.downloadmanager.data.repository.DownloadRepository
import com.arc.downloadmanager.data.repository.DownloadStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StatsViewModel
    @Inject
    constructor(
        private val repository: DownloadRepository,
    ) : ViewModel() {
        private val _stats = MutableStateFlow<DownloadStats?>(null)

        /** One-shot aggregate; null while the first load is in flight. */
        val stats: StateFlow<DownloadStats?> = _stats.asStateFlow()

        init {
            refresh()
        }

        /** Recomputes the aggregate from a fresh store snapshot (manual refresh). */
        fun refresh() {
            viewModelScope.launch {
                _stats.value = repository.downloadStats()
            }
        }
    }
