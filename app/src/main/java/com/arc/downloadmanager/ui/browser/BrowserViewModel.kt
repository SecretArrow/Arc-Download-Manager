package com.arc.downloadmanager.ui.browser

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arc.downloadmanager.data.db.BookmarkDao
import com.arc.downloadmanager.data.db.BookmarkEntity
import com.arc.downloadmanager.data.db.HistoryDao
import com.arc.downloadmanager.data.repository.DownloadRepository
import com.arc.downloadmanager.data.settings.SettingsRepository
import com.arc.downloadmanager.domain.model.DownloadRequest
import com.arc.downloadmanager.domain.model.DownloadSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String = "https://www.google.com",
    val title: String = "",
)

@HiltViewModel
class BrowserViewModel
    @Inject
    constructor(
        private val downloadRepository: DownloadRepository,
        private val bookmarkDao: BookmarkDao,
        private val historyDao: HistoryDao,
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _tabs = MutableStateFlow(listOf(BrowserTab()))
        val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

        private val _activeTabId = MutableStateFlow(_tabs.value.first().id)
        val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

        private val _addressText = MutableStateFlow("")
        val addressText: StateFlow<String> = _addressText.asStateFlow()

        private val _isLoading = MutableStateFlow(false)
        val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

        private val _progress = MutableStateFlow(0)
        val progress: StateFlow<Int> = _progress.asStateFlow()

        private val _privateMode = MutableStateFlow(false)
        val privateMode: StateFlow<Boolean> = _privateMode.asStateFlow()

        private val _desktopMode = MutableStateFlow(false)
        val desktopMode: StateFlow<Boolean> = _desktopMode.asStateFlow()

        private val _downloadEvents = MutableStateFlow<String?>(null)
        val downloadEvents: StateFlow<String?> = _downloadEvents.asStateFlow()

        /** Requests the current WebView to load or evaluate (consumed by the screen). */
        val loadRequests = MutableStateFlow<LoadRequest?>(null)

        val bookmarks =
            bookmarkDao.observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val history =
            historyDao.observeRecent(200)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val settings =
            settingsRepository.settings
                .stateIn(viewModelScope, SharingStarted.Eagerly, com.arc.downloadmanager.data.settings.EngineSettings())

        data class LoadRequest(val tabId: String, val url: String, val desktopMode: Boolean)

        fun activeTab(): BrowserTab? = _tabs.value.firstOrNull { it.id == _activeTabId.value }

        fun updateAddress(text: String) {
            _addressText.value = text
        }

        fun newTab(url: String = "https://www.google.com") {
            val tab = BrowserTab(url = url)
            _tabs.value = _tabs.value + tab
            _activeTabId.value = tab.id
            loadRequests.value = LoadRequest(tab.id, url, _desktopMode.value)
        }

        fun selectTab(tabId: String) {
            _activeTabId.value = tabId
            activeTab()?.let { tab ->
                _addressText.value = tab.url
                loadRequests.value = LoadRequest(tab.id, tab.url, _desktopMode.value)
            }
        }

        fun closeTab(tabId: String) {
            val remaining = _tabs.value.filterNot { it.id == tabId }
            if (remaining.isEmpty()) {
                val fresh = BrowserTab()
                _tabs.value = listOf(fresh)
                _activeTabId.value = fresh.id
                loadRequests.value = LoadRequest(fresh.id, fresh.url, _desktopMode.value)
            } else {
                _tabs.value = remaining
                if (_activeTabId.value == tabId) selectTab(remaining.first().id)
            }
        }

        fun submitAddress(raw: String) {
            val url = normalizeUrl(raw) ?: return
            _addressText.value = url
            _tabs.value = _tabs.value.map { if (it.id == _activeTabId.value) it.copy(url = url) else it }
            loadRequests.value = LoadRequest(_activeTabId.value, url, _desktopMode.value)
        }

        fun onNavigated(
            url: String,
            title: String,
        ) {
            _addressText.value = url
            _tabs.value =
                _tabs.value.map {
                    if (it.id == _activeTabId.value) it.copy(url = url, title = title) else it
                }
            viewModelScope.launch {
                historyDao.insert(url, title.ifBlank { url })
            }
        }

        fun setLoading(
            loading: Boolean,
            progressValue: Int = 0,
        ) {
            _isLoading.value = loading
            _progress.value = progressValue
        }

        fun togglePrivate() {
            _privateMode.value = !_privateMode.value
        }

        fun toggleDesktop() {
            _desktopMode.value = !_desktopMode.value
            activeTab()?.let { tab ->
                loadRequests.value = LoadRequest(tab.id, tab.url, _desktopMode.value)
            }
        }

        fun setDesktop(value: Boolean) {
            if (_desktopMode.value != value) toggleDesktop()
        }

        fun isBookmarked(url: String?): StateFlow<Boolean> =
            (url?.let { bookmarkDao.observeBookmarked(it) } ?: MutableStateFlow(false))
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), false)

        fun toggleBookmark(
            url: String?,
            title: String?,
        ) {
            if (url.isNullOrBlank()) return
            viewModelScope.launch {
                val isBookmarked = bookmarks.value.any { it.url == url }
                if (isBookmarked) {
                    bookmarkDao.delete(url)
                } else {
                    bookmarkDao.upsert(BookmarkEntity(url = url, title = title ?: url))
                }
            }
        }

        fun deleteBookmark(url: String) {
            viewModelScope.launch { bookmarkDao.delete(url) }
        }

        fun clearHistory() {
            viewModelScope.launch { historyDao.clear() }
        }

        /** Sends a browser-initiated download to the engine with session context. */
        fun downloadFromBrowser(
            url: String,
            userAgent: String?,
            contentDisposition: String?,
            mimeType: String?,
            referer: String?,
            cookies: String?,
        ) {
            viewModelScope.launch {
                val request =
                    DownloadRequest(
                        url = url,
                        referer = referer,
                        userAgent = userAgent,
                        cookies = cookies,
                        source = DownloadSource.BROWSER,
                    )
                downloadRepository.add(request, policyOverride = com.arc.downloadmanager.data.settings.OverwritePolicy.RENAME)
                _downloadEvents.value = url
            }
        }

        fun consumeDownloadEvent() {
            _downloadEvents.value = null
        }

        fun clearBrowsingData(onDone: () -> Unit) {
            viewModelScope.launch {
                historyDao.clear()
                onDone()
            }
        }

        fun consumeLoadRequest() {
            loadRequests.value = null
        }

        companion object {
            fun normalizeUrl(raw: String): String? {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return null
                val looksLikeUrl = trimmed.contains('.') && !trimmed.contains(' ')
                return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    if (Uri.parse(trimmed).host != null) trimmed else null
                } else if (looksLikeUrl) {
                    "https://$trimmed"
                } else {
                    "https://www.google.com/search?q=${Uri.encode(trimmed)}"
                }
            }
        }
    }
