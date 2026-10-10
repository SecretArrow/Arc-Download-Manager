package com.arc.downloadmanager.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arc.downloadmanager.data.settings.EngineSettings
import com.arc.downloadmanager.data.settings.OverwritePolicy
import com.arc.downloadmanager.data.settings.SettingsRepository
import com.arc.downloadmanager.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<EngineSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineSettings())

    fun setMaxConcurrent(value: Int) = launch { settingsRepository.setMaxConcurrentFiles(value) }

    fun setMaxSegments(value: Int) = launch { settingsRepository.setMaxSegmentsPerFile(value) }

    fun setAutoSegments(value: Boolean) = launch { settingsRepository.setAutoDetectSegments(value) }

    fun setWifiOnly(value: Boolean) = launch { settingsRepository.setWifiOnly(value) }

    fun setAutoResume(value: Boolean) = launch { settingsRepository.setAutoResume(value) }

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

    fun setDefaultDestination(uri: String?) = launch { settingsRepository.setDefaultDestination(uri) }

    fun reset() = launch {
        settingsRepository.setTheme(ThemeMode.SYSTEM)
        settingsRepository.setDynamicColor(true)
        settingsRepository.setCompactRows(false)
        settingsRepository.setMaxConcurrentFiles(3)
        settingsRepository.setMaxSegmentsPerFile(4)
        settingsRepository.setRetryAttempts(5)
        settingsRepository.setWifiOnly(false)
        settingsRepository.setBandwidthLimit(0)
        settingsRepository.setDefaultDestination(null)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
