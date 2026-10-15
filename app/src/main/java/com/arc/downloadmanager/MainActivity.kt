package com.arc.downloadmanager

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arc.downloadmanager.data.settings.SettingsRepository
import com.arc.downloadmanager.data.settings.ThemeMode
import com.arc.downloadmanager.service.DownloadServiceController
import com.arc.downloadmanager.ui.components.LockScreen
import com.arc.downloadmanager.ui.components.showBiometricPrompt
import com.arc.downloadmanager.ui.theme.ArcTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Time spent in the background after which the app locks again. */
private const val RELOCK_TIMEOUT_MS = 30_000L

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
    ) : ViewModel() {
        data class UiState(
            val theme: ThemeMode = ThemeMode.SYSTEM,
            val dynamicColor: Boolean = true,
            val appLock: Boolean = false,
        )

        val state: StateFlow<UiState> =
            settingsRepository.settings
                .map { UiState(theme = it.theme, dynamicColor = it.dynamicColor, appLock = it.appLock) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())
    }

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @Inject
    lateinit var serviceController: DownloadServiceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            val viewModel: MainViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val state by viewModel.state.collectAsState()

            // App-lock session state, shared between the process-level lifecycle
            // observer and composition via a simple snapshot-state holder.
            val lockState = remember { AppLockSessionState() }
            var unlocked by rememberSaveable { mutableStateOf(false) }

            // Relock when the process returns from >30 s in the background.
            DisposableEffect(Unit) {
                val observer =
                    object : DefaultLifecycleObserver {
                        override fun onStop(owner: LifecycleOwner) {
                            lockState.stoppedAt = SystemClock.elapsedRealtime()
                        }

                        override fun onStart(owner: LifecycleOwner) {
                            if (lockState.stoppedAt > 0 &&
                                SystemClock.elapsedRealtime() - lockState.stoppedAt > RELOCK_TIMEOUT_MS
                            ) {
                                lockState.relock = true
                            }
                        }
                    }
                ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
                onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
            }

            val locked = state.appLock && (!unlocked || lockState.relock)

            ArcTheme(themeMode = state.theme, dynamicColor = state.dynamicColor) {
                if (locked) {
                    var fallback by remember { mutableStateOf(false) }
                    var message by remember { mutableStateOf<String?>(null) }

                    val attemptUnlock: () -> Unit = {
                        // runCatching guards odd OEM/window states where the prompt
                        // cannot even be constructed. A `false` result means no
                        // usable authenticator (no hardware / nothing enrolled) —
                        // fall back to the session-only unlock so the user can
                        // never be permanently locked out of their own app.
                        val shown =
                            runCatching {
                                showBiometricPrompt(
                                    this@MainActivity,
                                    onSuccess = {
                                        unlocked = true
                                        lockState.relock = false
                                    },
                                    onError = { _, err -> message = err.toString() },
                                )
                            }.getOrDefault(false)
                        if (!shown) fallback = true
                    }

                    // Present the system prompt as soon as the lock screen appears;
                    // the Unlock button re-tries it after a cancel/failure.
                    LaunchedEffect(locked) { attemptUnlock() }

                    LockScreen(
                        fallback = fallback,
                        onUnlock = attemptUnlock,
                        onUnlockAnyway = {
                            // Session-only unlock; the lock re-arms on next launch.
                            unlocked = true
                            lockState.relock = false
                        },
                        message = message,
                    )
                } else {
                    com.arc.downloadmanager.ui.navigation.ArcApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Entering the foreground resets Android's 6-hour dataSync service timer,
        // so clear any exhausted-quota latch and auto-resume downloads that the
        // platform paused when the previous budget ran out.
        serviceController.onAppForeground()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Background timestamp + relock latch read (and cleared) from composition. */
    private class AppLockSessionState {
        var stoppedAt by mutableStateOf(0L)
        var relock by mutableStateOf(false)
    }
}
