package com.arc.downloadmanager

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arc.downloadmanager.data.settings.SettingsRepository
import com.arc.downloadmanager.data.settings.ThemeMode
import com.arc.downloadmanager.ui.theme.ArcTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {
    data class UiState(val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = true)

    val state: StateFlow<UiState> = settingsRepository.settings
        .map { UiState(theme = it.theme, dynamicColor = it.dynamicColor) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            val viewModel: MainViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val state by viewModel.state.collectAsState()
            ArcTheme(themeMode = state.theme, dynamicColor = state.dynamicColor) {
                com.arc.downloadmanager.ui.navigation.ArcApp()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
