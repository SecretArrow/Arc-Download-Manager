@file:OptIn(ExperimentalMaterial3Api::class)

package com.arc.downloadmanager.ui.browser

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arc.downloadmanager.R
import com.arc.downloadmanager.engine.CookieScopePolicy

@Composable
fun BrowserScreen(viewModel: BrowserViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val address by viewModel.addressText.collectAsStateWithLifecycle()
    val loading by viewModel.isLoading.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val privateMode by viewModel.privateMode.collectAsStateWithLifecycle()
    val desktopMode by viewModel.desktopMode.collectAsStateWithLifecycle()
    val downloadEvent by viewModel.downloadEvents.collectAsStateWithLifecycle()
    val loadRequest by viewModel.loadRequests.collectAsStateWithLifecycle()
    val appSettings by viewModel.settings.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var showTabs by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showLinkMenu by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        viewModel.setDesktop(appSettings.desktopModeDefault)
        if (appSettings.privateByDefault && !privateMode) viewModel.togglePrivate()
    }

    LaunchedEffect(downloadEvent) {
        downloadEvent?.let { url ->
            snackbar.showSnackbar(
                context.getString(R.string.browser_download_started, CookieScopePolicy.redact(url)),
            )
            viewModel.consumeDownloadEvent()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // WindowInsets(0): the browser has no TopAppBar, so the status-bar inset is applied
        // explicitly via statusBarsPadding() below; the navigation-bar inset is consumed by the
        // NavigationBar hosting this tab.
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                // Lift the whole browser above the keyboard while typing in the address bar,
                // so the URL field and bottom controls stay reachable (adjustResize + edge-to-edge).
                .imePadding(),
        ) {
            // Address bar row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = { showTabs = true }) {
                    BadgedBox(badge = { Badge { Text(tabs.size.toString()) } }) {
                        Icon(Icons.Filled.Tab, contentDescription = stringResource(R.string.browser_tabs))
                    }
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = viewModel::updateAddress,
                    placeholder = { Text(stringResource(R.string.browser_address_hint)) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            if (address.startsWith("https")) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.toggleBookmark(webViewRef?.url, webViewRef?.title) }) {
                    Icon(Icons.Filled.BookmarkBorder, contentDescription = stringResource(R.string.browser_bookmarks))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.browser_menu))
                    }
                    BrowserMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        onBookmarks = {
                            showBookmarks = true
                            menuOpen = false
                        },
                        onHistory = {
                            showHistory = true
                            menuOpen = false
                        },
                        onDesktop = {
                            viewModel.toggleDesktop()
                            menuOpen = false
                        },
                        onPrivate = {
                            viewModel.togglePrivate()
                            menuOpen = false
                        },
                        onClearData = {
                            menuOpen = false
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            webViewRef?.clearCache(true)
                            viewModel.clearBrowsingData { }
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.browser_cleared),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onShare = {
                            menuOpen = false
                            webViewRef?.url?.let { url ->
                                val intent =
                                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, url)
                                    }
                                context.startActivity(android.content.Intent.createChooser(intent, null))
                            }
                        },
                        onCopyLink = {
                            menuOpen = false
                            webViewRef?.url?.let { url ->
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                            }
                        },
                    )
                }
            }
            if (loading) {
                if (progress > 0) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // Page request started but no progress reported yet: show indeterminate.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            // WebView
            Box(Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams =
                                android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.userAgentString = null
                            setAcceptThirdParty()

                            webViewClient = ArcWebViewClient(viewModel)
                            webChromeClient =
                                object : WebChromeClient() {
                                    override fun onProgressChanged(
                                        view: WebView?,
                                        newProgress: Int,
                                    ) {
                                        viewModel.setLoading(newProgress < 100, newProgress)
                                    }
                                }
                            setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                                val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                                viewModel.downloadFromBrowser(
                                    url = url,
                                    userAgent = userAgent,
                                    contentDisposition = contentDisposition,
                                    mimeType = mimetype,
                                    referer = this.url,
                                    cookies = cookies,
                                )
                            }
                            setOnLongClickListener {
                                val result = hitTestResult
                                when (result.type) {
                                    android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE,
                                    -> {
                                        showLinkMenu = result.extra
                                        true
                                    }
                                    android.webkit.WebView.HitTestResult.IMAGE_TYPE,
                                    android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                                    -> {
                                        showLinkMenu = result.extra
                                        true
                                    }
                                    else -> false
                                }
                            }
                        }.also { webViewRef = it }
                    },
                    update = { webView ->
                        webView.settings.userAgentString = if (desktopMode) DESKTOP_UA else null
                        loadRequest?.let { request ->
                            if (request.tabId == activeTabId) {
                                webView.loadUrl(request.url)
                                viewModel.consumeLoadRequest()
                            }
                        }
                    },
                )
            }
            // Bottom controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
            ) {
                IconButton(onClick = { webViewRef?.goBack() }, enabled = webViewRef?.canGoBack() == true) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                IconButton(onClick = { webViewRef?.goForward() }, enabled = webViewRef?.canGoForward() == true) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.action_forward))
                }
                IconButton(onClick = { if (loading) webViewRef?.stopLoading() else webViewRef?.reload() }) {
                    Icon(if (loading) Icons.Filled.Stop else Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_reload))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showBookmarks = true }) {
                    Icon(Icons.Filled.Bookmark, contentDescription = stringResource(R.string.browser_bookmarks))
                }
                IconButton(onClick = { showHistory = true }) {
                    Icon(Icons.Filled.History, contentDescription = stringResource(R.string.browser_history))
                }
                IconButton(onClick = { showTabs = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.browser_new_tab))
                }
            }
        }
    }

    // Tabs sheet
    if (showTabs) {
        ModalBottomSheet(onDismissRequest = { showTabs = false }) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.browser_new_tab), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        viewModel.newTab()
                        showTabs = false
                    }) { Icon(Icons.Filled.Add, contentDescription = null) }
                    TextButton(onClick = {
                        viewModel.togglePrivate()
                    }) {
                        Icon(Icons.Filled.Shield, contentDescription = null)
                        Text(if (privateMode) "Private: ON" else "Private: OFF")
                    }
                }
                tabs.forEach { tab ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                viewModel.selectTab(tab.id)
                                showTabs = false
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                (tab.title.ifBlank { tab.url }).let { if (it.length > 48) it.take(48) + "…" else it },
                                color = if (tab.id == activeTabId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { viewModel.closeTab(tab.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.browser_close_tab))
                        }
                    }
                }
            }
        }
    }

    // Bookmarks sheet
    if (showBookmarks) {
        val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.browser_bookmarks), style = MaterialTheme.typography.titleMedium)
                bookmarks.forEach { bookmark ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                viewModel.submitAddress(bookmark.url)
                                showBookmarks = false
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(bookmark.title, maxLines = 1) }
                        IconButton(onClick = { viewModel.deleteBookmark(bookmark.url) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }

    // History sheet
    if (showHistory) {
        val history by viewModel.history.collectAsStateWithLifecycle()
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.browser_history), style = MaterialTheme.typography.titleMedium)
                history.forEach { entry ->
                    TextButton(
                        onClick = {
                            viewModel.submitAddress(entry.url)
                            showHistory = false
                        },
                    ) {
                        Text(entry.title.ifBlank { entry.url }, maxLines = 1)
                    }
                }
            }
        }
    }

    // Long-press link menu
    showLinkMenu?.let { link ->
        ModalBottomSheet(onDismissRequest = { showLinkMenu = null }) {
            Column(Modifier.padding(16.dp)) {
                Text(CookieScopePolicy.redact(link), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = {
                    viewModel.newTab(link)
                    showLinkMenu = null
                }) { Text(stringResource(R.string.browser_open_new_tab)) }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("link", link))
                    showLinkMenu = null
                }) { Text(stringResource(R.string.browser_copy_link)) }
                TextButton(onClick = {
                    val cookies = runCatching { CookieManager.getInstance().getCookie(link) }.getOrNull()
                    viewModel.downloadFromBrowser(
                        url = link,
                        userAgent = webViewRef?.settings?.userAgentString,
                        contentDisposition = null,
                        mimeType = null,
                        referer = webViewRef?.url,
                        cookies = cookies,
                    )
                    showLinkMenu = null
                }) { Text(stringResource(R.string.browser_download_link)) }
                TextButton(onClick = {
                    val intent =
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, link)
                        }
                    context.startActivity(android.content.Intent.createChooser(intent, null))
                    showLinkMenu = null
                }) { Text(stringResource(R.string.browser_share_link)) }
            }
        }
    }
}

@Composable
private fun BrowserMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onBookmarks: () -> Unit,
    onHistory: () -> Unit,
    onDesktop: () -> Unit,
    onPrivate: () -> Unit,
    onClearData: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.browser_bookmarks)) }, onClick = onBookmarks)
        DropdownMenuItem(text = { Text(stringResource(R.string.browser_history)) }, onClick = onHistory)
        DropdownMenuItem(text = { Text(stringResource(R.string.browser_desktop_site)) }, onClick = onDesktop)
        DropdownMenuItem(text = { Text(stringResource(R.string.browser_private_mode)) }, onClick = onPrivate)
        DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = onShare)
        DropdownMenuItem(text = { Text(stringResource(R.string.browser_copy_link)) }, onClick = onCopyLink)
        DropdownMenuItem(text = { Text(stringResource(R.string.browser_clear_data)) }, onClick = onClearData)
    }
}

private class ArcWebViewClient(
    private val viewModel: BrowserViewModel,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url.toString()
        val scheme = request.url.scheme?.lowercase() ?: ""
        if (scheme !in listOf("http", "https")) {
            // Let the system handle mailto:, intent:, market: etc.
            runCatching {
                view.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
            }
            return true
        }
        return false
    }

    override fun onPageStarted(
        view: WebView,
        url: String,
        favicon: Bitmap?,
    ) {
        viewModel.setLoading(true)
    }

    override fun onPageFinished(
        view: WebView,
        url: String,
    ) {
        viewModel.onNavigated(url, view.title ?: "")
        viewModel.setLoading(false, 100)
    }
}

private fun WebView.setAcceptThirdParty() {
    runCatching {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
