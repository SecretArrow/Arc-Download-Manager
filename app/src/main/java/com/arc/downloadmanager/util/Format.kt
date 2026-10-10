package com.arc.downloadmanager.util

import android.content.Context
import com.arc.downloadmanager.R
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/** Locale-aware formatting for sizes, speeds and remaining time. */
object Format {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < 1024) return "$value B"
        val unitIndex = (ln(value.toDouble()) / ln(1024.0)).toInt().coerceIn(1, UNITS.size - 1)
        val scaled = value / 1024.0.pow(unitIndex.toDouble())
        return String.format(Locale.getDefault(), "%.1f %s", scaled, UNITS[unitIndex])
    }

    fun speed(context: Context, bytesPerSecond: Long): String =
        context.getString(R.string.speed_format, bytes(bytesPerSecond.coerceAtLeast(0)))

    /** 0-100 progress or -1 when indeterminate. */
    fun percent(downloaded: Long, total: Long): Int =
        if (total <= 0) -1 else ((downloaded.toDouble() / total) * 100).toInt().coerceIn(0, 100)

    /** Human duration, e.g. "3m 20s". Returns null when not computable. */
    fun eta(context: Context, remainingBytes: Long, bytesPerSecond: Long): String? {
        if (bytesPerSecond <= 0 || remainingBytes <= 0) return null
        val seconds = (remainingBytes / bytesPerSecond).coerceAtMost(99L * 3600L)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val text = when {
            h > 0 -> String.format(Locale.getDefault(), "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.getDefault(), "%dm %02ds", m, s)
            else -> String.format(Locale.getDefault(), "%ds", s)
        }
        return context.getString(R.string.eta_format, text)
    }

    fun date(millis: Long): String {
        val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        return format.format(java.util.Date(millis))
    }
}
