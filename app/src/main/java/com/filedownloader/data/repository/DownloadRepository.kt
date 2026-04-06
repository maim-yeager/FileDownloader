package com.filedownloader.data.repository

import android.content.Context
import android.util.Log
import com.filedownloader.data.database.AppDatabase
import com.filedownloader.data.models.DownloadItem
import com.filedownloader.data.models.DownloadStatus
import kotlinx.coroutines.flow.Flow

private const val TAG = "DownloadRepository"

/**
 * Repository acting as single source of truth for download data.
 * Coordinates between database and network operations.
 */
class DownloadRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).downloadDao()

    // Expose all downloads as reactive Flow
    val allDownloads: Flow<List<DownloadItem>> = dao.getAllDownloads()
    val activeDownloads: Flow<List<DownloadItem>> = dao.getActiveDownloads()
    val completedDownloads: Flow<List<DownloadItem>> = dao.getCompletedDownloads()

    /**
     * Add a new download to the database
     * Returns the generated ID
     */
    suspend fun addDownload(item: DownloadItem): Long {
        return dao.insert(item)
    }

    /**
     * Update an existing download
     */
    suspend fun updateDownload(item: DownloadItem) {
        dao.update(item)
    }

    /**
     * Get download by ID
     */
    suspend fun getDownload(id: Long): DownloadItem? {
        return dao.getById(id)
    }

    /**
     * Update download progress
     */
    suspend fun updateProgress(id: Long, downloaded: Long, total: Long, speed: Long, status: DownloadStatus) {
        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        dao.updateProgress(id, downloaded, progress, speed, status)
    }

    /**
     * Update file info after server response
     */
    suspend fun updateFileInfo(id: Long, filePath: String, totalSize: Long, supportsRange: Boolean) {
        dao.updateFileInfo(id, filePath, totalSize, supportsRange)
    }

    /**
     * Mark download as completed
     */
    suspend fun markCompleted(id: Long) {
        dao.markCompleted(id)
    }

    /**
     * Mark download as failed with error message
     */
    suspend fun markFailed(id: Long, error: String) {
        Log.e(TAG, "Download $id failed: $error")
        dao.markFailed(id, error)
    }

    /**
     * Update download status
     */
    suspend fun updateStatus(id: Long, status: DownloadStatus) {
        dao.updateStatus(id, status)
    }

    /**
     * Delete a download record and optionally the file
     */
    suspend fun deleteDownload(id: Long) {
        dao.deleteById(id)
    }

    /**
     * Clear all completed download history
     */
    suspend fun clearHistory() {
        dao.clearCompleted()
    }

    /**
     * Get count of active downloads
     */
    suspend fun getActiveCount(): Int {
        return dao.getActiveCount()
    }

    companion object {
        @Volatile
        private var INSTANCE: DownloadRepository? = null

        fun getInstance(context: Context): DownloadRepository {
            return INSTANCE ?: synchronized(this) {
                DownloadRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
