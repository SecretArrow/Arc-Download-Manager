package com.arc.downloadmanager.engine

import java.util.Calendar
import java.util.Locale

/**
 * Pure time-window logic for scheduled downloads ("night quota"): decides whether downloads may
 * START at a given minute of day. No Android imports so the window math is fully unit-testable
 * on the JVM; the engine re-evaluates it on its existing 15 s ticker, so no AlarmManager is needed.
 *
 * A window is [start, end): start minute is inside, the end minute itself is outside. Windows may
 * wrap past midnight (22:00–06:00 covers 1320..1439 and 0..359); start == end means a 24 h window.
 */
object ScheduleWindow {
    /** First valid minute of a day (00:00). */
    const val MIN_MINUTE = 0

    /** Last valid minute of a day (23:59). */
    const val MAX_MINUTE = 23 * 60 + 59

    /**
     * True when downloads may start at [nowMinuteOfDay].
     *
     * @param enabled master switch — when false the window never blocks
     * @param startMinuteOfDay window start (inclusive), clamped to 0..1439
     * @param endMinuteOfDay window end (exclusive), clamped to 0..1439
     * @param nowMinuteOfDay the minute of day to test, clamped to 0..1439
     */
    fun activeNow(
        enabled: Boolean,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        nowMinuteOfDay: Int,
    ): Boolean {
        if (!enabled) return true
        val start = clamp(startMinuteOfDay)
        val end = clamp(endMinuteOfDay)
        val now = clamp(nowMinuteOfDay)
        if (start == end) return true // degenerate range = 24 h window
        return if (start < end) {
            now >= start && now < end
        } else {
            // Wraps past midnight, e.g. 22:00–06:00 → [1320, 1439] and [0, 359].
            now >= start || now < end
        }
    }

    /** Clamps a minute-of-day into 0..1439. */
    fun clamp(minute: Int): Int = minute.coerceIn(MIN_MINUTE, MAX_MINUTE)

    /** Renders a minute-of-day as "HH:mm" for the settings UI (always 24 h, locale-stable). */
    fun formatMinute(minute: Int): String {
        val m = clamp(minute)
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /** Parses "HH:mm" (lenient about leading zeros) into a minute-of-day; null when malformed. */
    fun parseMinute(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** Current minute of day on the device's wall clock. Injectable via [activeNow] in tests. */
    fun nowMinuteOfDay(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }
}
