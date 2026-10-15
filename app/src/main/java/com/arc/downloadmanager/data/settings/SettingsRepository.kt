package com.arc.downloadmanager.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "arc_settings")

@Singleton
class SettingsRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : com.arc.downloadmanager.engine.EngineSettingsSource {
        private object Keys {
            val MAX_CONCURRENT = intPreferencesKey("max_concurrent_files")
            val MAX_SEGMENTS = intPreferencesKey("max_segments_per_file")
            val AUTO_SEGMENTS = booleanPreferencesKey("auto_detect_segments")
            val WIFI_ONLY = booleanPreferencesKey("wifi_only")
            val AUTO_RESUME = booleanPreferencesKey("auto_resume")
            val AUTO_RETRY = booleanPreferencesKey("auto_retry")
            val NOTIFY_COMPLETION = booleanPreferencesKey("notify_completion")
            val RETRY_ATTEMPTS = intPreferencesKey("retry_attempts")
            val CONNECT_TIMEOUT = intPreferencesKey("connect_timeout_sec")
            val READ_TIMEOUT = intPreferencesKey("read_timeout_sec")
            val BANDWIDTH_LIMIT = longPreferencesKey("bandwidth_limit_kbps")
            val MIN_FREE_SPACE = longPreferencesKey("min_free_space_mb")
            val OVERWRITE_POLICY = stringPreferencesKey("overwrite_policy")
            val THEME = stringPreferencesKey("theme")
            val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
            val COMPACT_ROWS = booleanPreferencesKey("compact_rows")
            val ANIMATIONS = booleanPreferencesKey("animations")
            val DESKTOP_DEFAULT = booleanPreferencesKey("desktop_mode_default")
            val PRIVATE_DEFAULT = booleanPreferencesKey("private_by_default")
            val COMPUTE_SHA256 = booleanPreferencesKey("compute_sha256")
            val AUTO_CLEANUP_TEMP = booleanPreferencesKey("auto_cleanup_temp")
            val APP_LOCK = booleanPreferencesKey("app_lock")
            val DEFAULT_DESTINATION = stringPreferencesKey("default_destination_uri")
        }

        override val settings: Flow<EngineSettings> =
            context.dataStore.data.map { p ->
                EngineSettings(
                    maxConcurrentFiles = p[Keys.MAX_CONCURRENT] ?: 3,
                    maxSegmentsPerFile = p[Keys.MAX_SEGMENTS] ?: 4,
                    autoDetectSegments = p[Keys.AUTO_SEGMENTS] ?: true,
                    wifiOnly = p[Keys.WIFI_ONLY] ?: false,
                    autoResume = p[Keys.AUTO_RESUME] ?: true,
                    autoRetry = p[Keys.AUTO_RETRY] ?: true,
                    notifyCompletion = p[Keys.NOTIFY_COMPLETION] ?: true,
                    retryAttempts = p[Keys.RETRY_ATTEMPTS] ?: 5,
                    connectTimeoutSec = p[Keys.CONNECT_TIMEOUT] ?: 15,
                    readTimeoutSec = p[Keys.READ_TIMEOUT] ?: 30,
                    bandwidthLimitKBps = p[Keys.BANDWIDTH_LIMIT] ?: 0L,
                    minFreeSpaceMB = p[Keys.MIN_FREE_SPACE] ?: 200L,
                    overwritePolicy =
                        p[Keys.OVERWRITE_POLICY]?.let { runCatching { OverwritePolicy.valueOf(it) }.getOrNull() }
                            ?: OverwritePolicy.RENAME,
                    theme = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                    dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
                    compactRows = p[Keys.COMPACT_ROWS] ?: false,
                    animations = p[Keys.ANIMATIONS] ?: true,
                    desktopModeDefault = p[Keys.DESKTOP_DEFAULT] ?: false,
                    privateByDefault = p[Keys.PRIVATE_DEFAULT] ?: false,
                    computeSha256 = p[Keys.COMPUTE_SHA256] ?: false,
                    autoCleanupTemp = p[Keys.AUTO_CLEANUP_TEMP] ?: true,
                    appLock = p[Keys.APP_LOCK] ?: false,
                    defaultDestinationUri = p[Keys.DEFAULT_DESTINATION],
                )
            }

        override suspend fun snapshot(): EngineSettings = settings.first()

        suspend fun setMaxConcurrentFiles(value: Int) = edit { it[Keys.MAX_CONCURRENT] = value.coerceIn(1, 10) }

        suspend fun setMaxSegmentsPerFile(value: Int) = edit { it[Keys.MAX_SEGMENTS] = value.coerceIn(1, 16) }

        suspend fun setAutoDetectSegments(value: Boolean) = edit { it[Keys.AUTO_SEGMENTS] = value }

        suspend fun setWifiOnly(value: Boolean) = edit { it[Keys.WIFI_ONLY] = value }

        suspend fun setAutoResume(value: Boolean) = edit { it[Keys.AUTO_RESUME] = value }

        suspend fun setAutoRetry(value: Boolean) = edit { it[Keys.AUTO_RETRY] = value }

        suspend fun setNotifyCompletion(value: Boolean) = edit { it[Keys.NOTIFY_COMPLETION] = value }

        suspend fun setRetryAttempts(value: Int) = edit { it[Keys.RETRY_ATTEMPTS] = value.coerceIn(0, 20) }

        suspend fun setConnectTimeout(value: Int) = edit { it[Keys.CONNECT_TIMEOUT] = value.coerceIn(5, 120) }

        suspend fun setReadTimeout(value: Int) = edit { it[Keys.READ_TIMEOUT] = value.coerceIn(5, 600) }

        suspend fun setBandwidthLimit(kbps: Long) = edit { it[Keys.BANDWIDTH_LIMIT] = kbps.coerceAtLeast(0) }

        suspend fun setMinFreeSpace(mb: Long) = edit { it[Keys.MIN_FREE_SPACE] = mb.coerceAtLeast(50) }

        suspend fun setOverwritePolicy(policy: OverwritePolicy) = edit { it[Keys.OVERWRITE_POLICY] = policy.name }

        suspend fun setTheme(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }

        suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = value }

        suspend fun setCompactRows(value: Boolean) = edit { it[Keys.COMPACT_ROWS] = value }

        suspend fun setAnimations(value: Boolean) = edit { it[Keys.ANIMATIONS] = value }

        suspend fun setDesktopModeDefault(value: Boolean) = edit { it[Keys.DESKTOP_DEFAULT] = value }

        suspend fun setPrivateByDefault(value: Boolean) = edit { it[Keys.PRIVATE_DEFAULT] = value }

        suspend fun setComputeSha256(value: Boolean) = edit { it[Keys.COMPUTE_SHA256] = value }

        suspend fun setAutoCleanupTemp(value: Boolean) = edit { it[Keys.AUTO_CLEANUP_TEMP] = value }

        suspend fun setAppLock(value: Boolean) = edit { it[Keys.APP_LOCK] = value }

        suspend fun setDefaultDestination(uri: String?) =
            edit { p ->
                if (uri == null) p.remove(Keys.DEFAULT_DESTINATION) else p[Keys.DEFAULT_DESTINATION] = uri
            }

        private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
            context.dataStore.edit { block(it) }
        }
    }
