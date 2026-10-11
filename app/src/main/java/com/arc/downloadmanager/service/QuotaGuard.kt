package com.arc.downloadmanager.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the Android 15+ six-hour dataSync foreground-service quota.
 *
 * Android permits dataSync foreground services to run for a total of 6 hours in any
 * 24-hour period (the budget is shared by every dataSync service of the app). Once the
 * budget is gone:
 *  - starting another dataSync service throws ForegroundServiceStartNotAllowedException
 *    ("Time limit already exhausted for foreground service type dataSync"), and
 *  - the running service receives [android.app.Service.onTimeout] and must call
 *    stopSelf() within a few seconds or the process is killed with RemoteServiceException.
 *
 * The six-hour timer RESETS when the user brings the app to the foreground, so
 * [clearOnForeground] is called from MainActivity.onStart — after that, starting the
 * service is allowed again with a fresh budget, and downloads paused by the timeout
 * can be resumed automatically.
 */
@Singleton
class QuotaGuard
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** In-process latch: stop attempting foreground-service starts until the app is next foregrounded. */
        @Volatile
        var skipStarts = false
            private set

        /** Records that the dataSync quota is exhausted and foreground-service starts must stop. */
        fun markExhausted() {
            skipStarts = true
            prefs.edit().putLong(KEY_EXHAUSTED_AT, System.currentTimeMillis()).apply()
        }

        /** True when a timeout was recorded within the rolling 24-hour window and no reset happened yet. */
        fun isExhausted(): Boolean {
            val at = prefs.getLong(KEY_EXHAUSTED_AT, 0L)
            return isExhaustedAt(at, System.currentTimeMillis())
        }

        /**
         * Resets the latch and the recorded window. Per the platform docs, bringing the app
         * to the foreground resets the six-hour timer and grants a fresh budget.
         */
        fun clearOnForeground() {
            skipStarts = false
            prefs.edit().remove(KEY_EXHAUSTED_AT).apply()
        }

        /** Remembers which downloads were paused because of the timeout so they can auto-resume. */
        fun rememberQuotaPaused(ids: Collection<String>) {
            if (ids.isEmpty()) return
            val merged = quotaPausedIds() + ids
            prefs.edit().putStringSet(KEY_PAUSED_IDS, merged).apply()
        }

        fun quotaPausedIds(): Set<String> = prefs.getStringSet(KEY_PAUSED_IDS, emptySet()) ?: emptySet()

        /** Returns the quota-paused download ids recorded earlier and clears them. */
        fun consumeQuotaPausedIds(): Set<String> {
            val ids = quotaPausedIds()
            if (ids.isNotEmpty()) prefs.edit().remove(KEY_PAUSED_IDS).apply()
            return ids
        }

        companion object {
            private const val PREFS = "fgs_quota"
            private const val KEY_EXHAUSTED_AT = "exhausted_at"
            private const val KEY_PAUSED_IDS = "quota_paused_ids"

            /** Platform enforcement window for the dataSync runtime budget. */
            const val WINDOW_MS: Long = 24L * 60 * 60 * 1000

            /** Pure decision used by [isExhausted]; exposed for unit testing. */
            fun isExhaustedAt(
                exhaustedAtMs: Long,
                nowMs: Long,
                windowMs: Long = WINDOW_MS,
            ): Boolean = exhaustedAtMs > 0 && nowMs - exhaustedAtMs in 0 until windowMs
        }
    }
