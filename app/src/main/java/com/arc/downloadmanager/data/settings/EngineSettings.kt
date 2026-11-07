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
    val cellularLimitKBps: Long = 0L,
    /** Scheduled downloads ("night quota"): only start inside the [scheduleStartMin, scheduleEndMin) window. */
    val scheduleEnabled: Boolean = false,
    /** Window start in minutes-of-day (default 22:00). */
    val scheduleStartMin: Int = 1320,
    /** Window end in minutes-of-day (default 06:00); may wrap past midnight. */
    val scheduleEndMin: Int = 360,
    val minFreeSpaceMB: Long = 200L,
    /** The single collision policy (Settings radio): RENAME saves "name (1).ext" silently,
     *  REPLACE overwrites the old artifact, SKIP parks the task in CONFLICT and asks. */
    val overwritePolicy: OverwritePolicy = OverwritePolicy.RENAME,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val compactRows: Boolean = false,
    val animations: Boolean = true,
    val desktopModeDefault: Boolean = false,
    val privateByDefault: Boolean = false,
    val computeSha256: Boolean = false,
    val autoCleanupTemp: Boolean = true,
    /**
     * Retired per-switch toggle, kept only as a legacy alias: users who enabled it keep the
     * silent "name (1).ext" behavior (OR-ed into [overwritePolicy] by [com.arc.downloadmanager.engine.CollisionPolicy]).
     * The Settings UI no longer exposes it — the policy radio is the single control.
     */
    val autoRenameDuplicates: Boolean = false,
    val appLock: Boolean = false,
    val defaultDestinationUri: String? = null,
)

enum class OverwritePolicy { RENAME, SKIP, REPLACE }

enum class ThemeMode { SYSTEM, LIGHT, DARK }
