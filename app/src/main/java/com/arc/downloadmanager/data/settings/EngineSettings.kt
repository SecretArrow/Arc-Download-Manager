package com.arc.downloadmanager.data.settings

/**
 * Snapshot of user-configurable engine + app settings.
 */
data class EngineSettings(
    val maxConcurrentFiles: Int = 3,
    val maxSegmentsPerFile: Int = 4,
    val autoDetectSegments: Boolean = true,
    val wifiOnly: Boolean = false,
    val autoResume: Boolean = true,
    val autoRetry: Boolean = true,
    val notifyCompletion: Boolean = true,
    val retryAttempts: Int = 5,
    val connectTimeoutSec: Int = 15,
    val readTimeoutSec: Int = 30,
    val bandwidthLimitKBps: Long = 0L,
    val minFreeSpaceMB: Long = 200L,
    val overwritePolicy: OverwritePolicy = OverwritePolicy.RENAME,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val compactRows: Boolean = false,
    val animations: Boolean = true,
    val desktopModeDefault: Boolean = false,
    val privateByDefault: Boolean = false,
    val computeSha256: Boolean = false,
    val autoCleanupTemp: Boolean = true,
    val appLock: Boolean = false,
    val defaultDestinationUri: String? = null,
)

enum class OverwritePolicy { RENAME, SKIP, REPLACE }

enum class ThemeMode { SYSTEM, LIGHT, DARK }
