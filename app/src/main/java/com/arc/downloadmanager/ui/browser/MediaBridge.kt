package com.arc.downloadmanager.ui.browser

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONArray

/**
 * JS bridge injected into every page as `window.ArcMediaBridge`. The page script
 * ([MEDIA_SNIPPET]) collects candidate media URLs and posts them here as a JSON array.
 * [receive] runs on a WebView background thread, so parsing is defensive and the
 * callback is forwarded on the MAIN thread.
 */
class MediaBridge(
    val onFound: (List<String>) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun receive(json: String) {
        val urls =
            runCatching {
                val array = JSONArray(json)
                buildList {
                    for (i in 0 until array.length()) {
                        val candidate = array.opt(i)?.toString() ?: continue
                        if (candidate.startsWith("http://") || candidate.startsWith("https://")) add(candidate)
                    }
                }
            }.getOrDefault(emptyList())
        mainHandler.post { onFound(urls) }
    }
}

/**
 * Collects candidate media URLs from the current document: `src` of `video`/`audio`
 * elements (including their `source` children) plus links whose href points at a media
 * file extension. The result is posted to [MediaBridge]. Run it with
 * [android.webkit.WebView.evaluateJavascript] from `WebViewClient.onPageFinished`.
 */
const val MEDIA_SNIPPET: String =
    "(function(){try{var out=[];" +
        "document.querySelectorAll('video source, audio source, video, audio')" +
        ".forEach(function(e){var s=e.src||e.getAttribute('src');if(s&&s.indexOf('http')===0)out.push(s)});" +
        "document.querySelectorAll('a[href]')" +
        ".forEach(function(a){var h=a.href||'';" +
        "if(/\\.(mp4|m4a|mp3|wav|ogg|opus|webm|mkv|mov|avi|aac|flv|m3u8|ts)(\\?|#|$)/i.test(h))out.push(h)});" +
        "window.ArcMediaBridge.receive(JSON.stringify(out))}catch(e){}})()"
