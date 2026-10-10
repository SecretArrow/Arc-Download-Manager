package com.arc.downloadmanager.ui.files

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arc.downloadmanager.data.repository.FileCategory
import com.arc.downloadmanager.data.repository.FileItem
import com.arc.downloadmanager.data.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FilesViewModel
    @Inject
    constructor(
        private val fileRepository: FileRepository,
    ) : ViewModel() {
        private val _files = MutableStateFlow<List<FileItem>>(emptyList())
        val files: StateFlow<List<FileItem>> = _files.asStateFlow()

        private val _category = MutableStateFlow<FileCategory?>(null)
        val category: StateFlow<FileCategory?> = _category.asStateFlow()

        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                _files.value = runCatching { fileRepository.listAll() }.getOrDefault(emptyList())
            }
        }

        fun selectCategory(category: FileCategory?) {
            _category.value = category
        }

        fun search(query: String) {
            _query.value = query
        }

        fun visibleFiles(): List<FileItem> =
            _files.value
                .filter { _category.value == FileCategory.OTHER || it.category == _category.value || _category.value == FileCategory.values().first() }
                .filter { _query.value.isBlank() || it.name.contains(_query.value, ignoreCase = true) }

        fun delete(item: FileItem) {
            viewModelScope.launch {
                fileRepository.delete(item.uri)
                refresh()
            }
        }

        fun rename(
            item: FileItem,
            newName: String,
            onDone: (Boolean) -> Unit,
        ) {
            viewModelScope.launch {
                val ok = fileRepository.rename(item.uri, newName)
                refresh()
                onDone(ok)
            }
        }

        fun openIntent(item: FileItem): Intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(item.uri), item.mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        fun shareIntent(item: FileItem): Intent =
            Intent(Intent.ACTION_SEND).apply {
                type = item.mime ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(item.uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }
