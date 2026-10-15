package com.arc.downloadmanager.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arc.downloadmanager.data.repository.ExportResult
import com.arc.downloadmanager.data.repository.ImportResult
import com.arc.downloadmanager.data.repository.TaskBackup
import com.arc.downloadmanager.data.settings.EngineSettings
import com.arc.downloadmanager.data.settings.OverwritePolicy
import com.arc.downloadmanager.data.settings.SettingsRepository
import com.arc.downloadmanager.data.settings.ThemeMode
import com.arc.downloadmanager.data.update.UpdateChecker
import com.arc.downloadmanager.data.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val updateChecker: UpdateChecker,
        private val taskBackup: TaskBackup,
    ) : ViewModel() {
        val settings: StateFlow<EngineSettings> =
            settingsRepository.settings
                .stateIn(viewModelScope, SharingStarted.Eagerly, EngineSettings())

        // Update checker (GitHub releases)
        private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
        val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

        private val _checking = MutableStateFlow(false)
        val checking: StateFlow<Boolean> = _checking.asStateFlow()

        private val _updateDismissed = MutableStateFlow(false)
        val updateDismissed: StateFlow<Boolean> = _updateDismissed.asStateFlow()

        /** One-shot tick: incremented after a manual check that found nothing newer. */
        private val _upToDateTick = MutableStateFlow(0)
        val upToDateTick: StateFlow<Int> = _upToDateTick.asStateFlow()

        init {
            refreshUpdate(manual = false)
        }

        fun checkNow() = refreshUpdate(manual = true)

        fun dismissUpdate() {
            _updateDismissed.value = true
        }

        private fun refreshUpdate(manual: Boolean) {
            if (_checking.value) return
            viewModelScope.launch {
                _checking.value = true
                val info = runCatching { updateChecker.check() }.getOrNull()
                _updateInfo.value = info
                _checking.value = false
                if (manual) {
                    _updateDismissed.value = false
                    if (info == null) _upToDateTick.value += 1
                }
            }
        }

        // Task list backup pass-throughs (UI calls these in its own scope).
        suspend fun exportTasks(): ExportResult = taskBackup.export()

        suspend fun importTasks(text: String): ImportResult = taskBackup.importJson(text)

        fun setMaxConcurrent(value: Int) = launch { settingsRepository.setMaxConcurrentFiles(value) }

        fun setMaxSegments(value: Int) = launch { settingsRepository.setMaxSegmentsPerFile(value) }

        fun setAutoSegments(value: Boolean) = launch { settingsRepository.setAutoDetectSegments(value) }

        fun setWifiOnly(value: Boolean) = launch { settingsRepository.setWifiOnly(value) }

        fun setAutoResume(value: Boolean) = launch { settingsRepository.setAutoResume(value) }

        fun setAutoRetry(value: Boolean) = launch { settingsRepository.setAutoRetry(value) }

        fun setNotifyCompletion(value: Boolean) = launch { settingsRepository.setNotifyCompletion(value) }

        fun setRetries(value: Int) = launch { settingsRepository.setRetryAttempts(value) }

        fun setConnectTimeout(value: Int) = launch { settingsRepository.setConnectTimeout(value) }

        fun setReadTimeout(value: Int) = launch { settingsRepository.setReadTimeout(value) }

        fun setBandwidthLimit(kbps: Long) = launch { settingsRepository.setBandwidthLimit(kbps) }

        fun setMinFreeSpace(mb: Long) = launch { settingsRepository.setMinFreeSpace(mb) }

        fun setOverwritePolicy(policy: OverwritePolicy) = launch { settingsRepository.setOverwritePolicy(policy) }

        fun setTheme(mode: ThemeMode) = launch { settingsRepository.setTheme(mode) }

        fun setDynamicColor(value: Boolean) = launch { settingsRepository.setDynamicColor(value) }

        fun setCompactRows(value: Boolean) = launch { settingsRepository.setCompactRows(value) }

        fun setAnimations(value: Boolean) = launch { settingsRepository.setAnimations(value) }

        fun setDesktopDefault(value: Boolean) = launch { settingsRepository.setDesktopModeDefault(value) }

        fun setPrivateDefault(value: Boolean) = launch { settingsRepository.setPrivateByDefault(value) }

        fun setComputeSha256(value: Boolean) = launch { settingsRepository.setComputeSha256(value) }

        fun setAutoCleanup(value: Boolean) = launch { settingsRepository.setAutoCleanupTemp(value) }

        // Called with true only AFTER a successful biometric/device-credential
        // verification in the UI layer; false persists immediately.
        fun setAppLock(value: Boolean) = launch { settingsRepository.setAppLock(value) }

        fun setDefaultDestination(uri: String?) = launch { settingsRepository.setDefaultDestination(uri) }

        fun reset() =
            launch {
                settingsRepository.setTheme(ThemeMode.SYSTEM)
                settingsRepository.setDynamicColor(true)
                settingsRepository.setCompactRows(false)
                settingsRepository.setMaxConcurrentFiles(3)
                settingsRepository.setMaxSegmentsPerFile(4)
                settingsRepository.setRetryAttempts(5)
                settingsRepository.setAutoRetry(true)
                settingsRepository.setWifiOnly(false)
                settingsRepository.setBandwidthLimit(0)
                settingsRepository.setDefaultDestination(null)
            }

        private fun launch(block: suspend () -> Unit) {
            viewModelScope.launch { block() }
        }
    }
