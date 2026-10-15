package com.arc.downloadmanager.util

import android.util.Patterns
import com.arc.downloadmanager.engine.MagnetUri

/**
 * Extracts the first shareable link from free-form text (share sheets, clipboard).
 *
 * Shared text is usually prose ("Check this out: https://… huge file!"), so the text is
 * scanned word by word: a word only counts when it carries an explicit http/https scheme
 * (a bare "example.com" mention is not a link the user meant to share) and survives
 * [Patterns.WEB_URL] validation. magnet:? links are accepted too — they never match WEB_URL.
 */
object LinkText {
    private const val MAGNET_PREFIX = "magnet:?"
    private const val HTTP_PREFIX = "http://"
    private const val HTTPS_PREFIX = "https://"
    private val WHITESPACE = Regex("\\s+")

    /** First http/https or magnet link found in [text], or null when there is none. */
    fun firstUrl(text: String?): String? {
        // Non-breaking spaces glue words together for Regex \s and String.trim, so normalize first.
        val trimmed = text?.replace('\u00A0', ' ')?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        for (raw in trimmed.split(WHITESPACE)) {
            val word = raw.trim('"', '\'', '`', '(', ')', '[', ']', '{', '}', '<', '>', ',', '.', ';', ':', '!', '?')
            if (word.isEmpty()) continue
            when {
                word.startsWith(MAGNET_PREFIX) && MagnetUri.isMagnet(word) -> return word
                word.startsWith(HTTPS_PREFIX, ignoreCase = true) || word.startsWith(HTTP_PREFIX, ignoreCase = true) ->
                    if (Patterns.WEB_URL.matcher(word).matches()) return word
            }
        }
        return null
    }
}
