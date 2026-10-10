package com.arc.downloadmanager.browser

import android.webkit.CookieManager
import com.arc.downloadmanager.engine.CookieProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies live WebView session cookies for a URL. Cookies never reach logs, notifications
 * or exports: they flow only into the download request headers.
 */
@Singleton
class WebViewCookieProvider @Inject constructor() : CookieProvider {

    override fun cookiesFor(url: String): String? = runCatching {
        val manager = CookieManager.getInstance()
        if (!manager.hasCookies()) null else manager.getCookie(url)
    }.getOrNull()

    /** Clears cookies for the given origin (used by "clear browsing data"). */
    fun clearFor(url: String) {
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
    }
}
