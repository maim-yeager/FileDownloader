package com.filedownloader.ui.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.filedownloader.data.models.DownloadItem
import com.filedownloader.data.models.DownloadStatus
import com.filedownloader.data.repository.DownloadRepository
import com.filedownloader.utils.UrlInfo
import com.filedownloader.utils.UrlInfoFetcher
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val TAG = "MainViewModel"

/**
 * UI state for the main screen
 */
sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class UrlInfoLoaded(val info: UrlInfo) : UiState()
    data class Error(val message: String) : UiState()
}

/**
 * ViewModel for the main activity.
 * Manages download list and URL analysis.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DownloadRepository.getInstance(application)

    // OkHttp for URL probing
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val urlFetcher = UrlInfoFetcher(httpClient)

    // UI state flow
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // All downloads
    val allDownloads = repository.allDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active downloads only
    val activeDownloads = repository.activeDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Thread count setting (default 4)
    private val _threadCount = MutableStateFlow(4)
    val threadCount: StateFlow<Int> = _threadCount.asStateFlow()

    /**
     * Analyze a URL before downloading (shows file info)
     */
    fun analyzeUrl(url: String) {
        if (url.isBlank()) {
            _uiState.value = UiState.Error("Please enter a URL")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val info = urlFetcher.fetchInfo(url)
                _uiState.value = UiState.UrlInfoLoaded(info)
            } catch (e: Exception) {
                Log.e(TAG, "URL analysis failed", e)
                _uiState.value = UiState.Error(e.message ?: "Failed to analyze URL")
            }
        }
    }

    /**
     * Add a download to the queue
     */
    fun addDownload(urlInfo: UrlInfo): Flow<Long> = flow {
        val item = DownloadItem(
            url = urlInfo.url,
            fileName = urlInfo.fileName,
            fileExtension = urlInfo.extension,
            totalSize = urlInfo.fileSize,
            mimeType = urlInfo.mimeType,
            supportsRange = urlInfo.supportsRange,
            threadCount = _threadCount.value,
            status = DownloadStatus.QUEUED
        )
        val id = repository.addDownload(item)
        emit(id)
    }

    /**
     * Reset UI state to idle
     */
    fun resetState() {
        _uiState.value = UiState.Idle
    }

    /**
     * Update thread count setting
     */
    fun setThreadCount(count: Int) {
        _threadCount.value = count.coerceIn(1, 8)
    }

    /**
     * Delete a download and optionally its file
     */
    fun deleteDownload(item: DownloadItem, deleteFile: Boolean = false) {
        viewModelScope.launch {
            if (deleteFile) {
                com.filedownloader.utils.FileUtils.deleteFile(item.filePath)
            }
            repository.deleteDownload(item.id)
        }
    }

    /**
     * Clear completed download history
     */
    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }
}
