package com.arc.downloadmanager.engine

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide holder of the currently effective speed limit in KB/s (0 = unlimited).
 *
 * [DownloadEngine] publishes the limit that matches the active network — the Wi-Fi
 * (all networks) limit normally, the cellular limit while Wi-Fi is off — and
 * [TaskRunner] reads it on every throttle check, so a running transfer immediately
 * honors setting or network changes without being restarted.
 */
@Singleton
class SpeedLimitHolder
    @Inject
    constructor() {
        @Volatile
        var limitKBps: Long = 0L
    }
