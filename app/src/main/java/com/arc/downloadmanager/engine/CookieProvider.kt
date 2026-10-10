package com.arc.downloadmanager.engine

/** Supplies session cookies for a URL at download time, keeping auth data out of the engine's logs. */
interface CookieProvider {
    /** Returns the raw Cookie header value for [url], or null when no session applies. */
    fun cookiesFor(url: String): String?
}
