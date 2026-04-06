package com.filedownloader.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.filedownloader.R
import com.filedownloader.data.models.DownloadItem
import com.filedownloader.data.models.DownloadStatus
import com.filedownloader.data.repository.DownloadRepository
import com.filedownloader.ui.activities.MainActivity
import com.filedownloader.utils.ChunkDownloader
import com.filedownloader.utils.DownloadProgressCallback
import com.filedownloader.utils.FileUtils
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "DownloadService"
private const val NOTIFICATION_CHANNEL_ID = "download_channel"
private const val NOTIFICATION_CHANNEL_NAME = "File Downloads"
private const val FOREGROUND_NOTIFICATION_ID = 1001

/**
 * Foreground service that manages all download operations.
 * Runs as a foreground service to ensure downloads continue in background.
 */
class DownloadService : LifecycleService() {

    // Binder for activity to service communication
    inner class DownloadBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    private val binder = DownloadBinder()
    private lateinit var repository: DownloadRepository
    private lateinit var notificationManager: NotificationManager

    // OkHttp client optimized for downloading
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Map of active downloaders keyed by download ID
    private val activeDownloaders = ConcurrentHashMap<Long, ChunkDownloader>()
    private val downloadJobs = ConcurrentHashMap<Long, Job>()

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository.getInstance(this)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
        Log.d(TAG, "Download service created")
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) startDownload(downloadId)
            }
            ACTION_PAUSE_DOWNLOAD -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) pauseDownload(downloadId)
            }
            ACTION_RESUME_DOWNLOAD -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) resumeDownload(downloadId)
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) cancelDownload(downloadId)
            }
            ACTION_STOP_SERVICE -> stopSelf()
        }

        return START_STICKY
    }

    /**
     * Start a download by ID
     */
    fun startDownload(downloadId: Long) {
        val job = lifecycleScope.launch {
            val item = repository.getDownload(downloadId) ?: return@launch
            executeDownload(item)
        }
        downloadJobs[downloadId] = job
    }

    /**
     * Execute the actual download for an item
     */
    private suspend fun executeDownload(item: DownloadItem) {
        val downloadId = item.id

        try {
            // Mark as connecting
            repository.updateStatus(downloadId, DownloadStatus.CONNECTING)
            showProgressNotification(downloadId, item.fileName, 0, "Connecting...")

            // Set up download directory and file path
            val downloadDir = FileUtils.getDownloadDirectory(this)
            val destFile = FileUtils.getUniqueFilePath(downloadDir, item.fileName)

            // Update file path in DB
            repository.updateFileInfo(downloadId, destFile.absolutePath, item.totalSize, item.supportsRange)

            // Create chunk downloader with progress callback
            val downloader = ChunkDownloader(
                client = httpClient,
                url = item.url,
                destFile = destFile,
                threadCount = item.threadCount,
                callback = object : DownloadProgressCallback {
                    override fun onProgress(downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) {
                        lifecycleScope.launch {
                            repository.updateProgress(
                                id = downloadId,
                                downloaded = downloadedBytes,
                                total = totalBytes,
                                speed = speedBytesPerSec,
                                status = DownloadStatus.DOWNLOADING
                            )

                            val progress = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                            val speedStr = FileUtils.formatSpeed(speedBytesPerSec)
                            showProgressNotification(downloadId, item.fileName, progress, "$speedStr • ${FileUtils.formatFileSize(downloadedBytes)}/${FileUtils.formatFileSize(totalBytes)}")
                        }
                    }

                    override fun onCompleted() {
                        lifecycleScope.launch {
                            repository.markCompleted(downloadId)
                            showCompletedNotification(downloadId, item.fileName, destFile.absolutePath)
                            activeDownloaders.remove(downloadId)
                            checkAndStopIfIdle()
                        }
                    }

                    override fun onFailed(error: String) {
                        lifecycleScope.launch {
                            repository.markFailed(downloadId, error)
                            showFailedNotification(downloadId, item.fileName, error)
                            activeDownloaders.remove(downloadId)
                            checkAndStopIfIdle()
                        }
                    }

                    override fun onPaused() {
                        lifecycleScope.launch {
                            repository.updateStatus(downloadId, DownloadStatus.PAUSED)
                            cancelNotification(downloadId)
                        }
                    }
                }
            )

            activeDownloaders[downloadId] = downloader

            // Start the actual download
            repository.updateStatus(downloadId, DownloadStatus.DOWNLOADING)
            downloader.start()

        } catch (e: CancellationException) {
            // Job was cancelled - don't update status (already handled by cancel())
            Log.d(TAG, "Download $downloadId was cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in download $downloadId", e)
            repository.markFailed(downloadId, "Unexpected error: ${e.message}")
            activeDownloaders.remove(downloadId)
        }
    }

    /**
     * Pause a download
     */
    fun pauseDownload(downloadId: Long) {
        activeDownloaders[downloadId]?.pause()
    }

    /**
     * Resume a paused download
     */
    fun resumeDownload(downloadId: Long) {
        val downloader = activeDownloaders[downloadId]
        if (downloader != null) {
            downloader.resume()
            lifecycleScope.launch {
                repository.updateStatus(downloadId, DownloadStatus.DOWNLOADING)
            }
        } else {
            // Re-start download from saved state
            startDownload(downloadId)
        }
    }

    /**
     * Cancel and clean up a download
     */
    fun cancelDownload(downloadId: Long) {
        activeDownloaders[downloadId]?.cancel()
        downloadJobs[downloadId]?.cancel()
        activeDownloaders.remove(downloadId)
        downloadJobs.remove(downloadId)

        lifecycleScope.launch {
            val item = repository.getDownload(downloadId)
            repository.updateStatus(downloadId, DownloadStatus.CANCELLED)
            // Delete partial download file
            item?.filePath?.let { FileUtils.deleteFile(it) }
            cancelNotification(downloadId)
        }
    }

    // ==================== NOTIFICATION HELPERS ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress"
                setShowBadge(true)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("File Downloader")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    private fun showProgressNotification(downloadId: Long, fileName: String, progress: Int, statusText: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, downloadId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_PAUSE_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        }
        val pausePendingIntent = PendingIntent.getService(
            this, (downloadId * 10 + 1).toInt(), pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, (downloadId * 10 + 2).toInt(), cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(fileName.take(40))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .setSilent(true)
            .setOngoing(true)
            .build()

        notificationManager.notify(FOREGROUND_NOTIFICATION_ID + downloadId.toInt(), notification)
    }

    private fun showCompletedNotification(downloadId: Long, fileName: String, filePath: String) {
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            val file = java.io.File(filePath)
            if (file.exists()) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@DownloadService,
                    "${packageName}.fileprovider",
                    file
                )
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, downloadId.toInt(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(FOREGROUND_NOTIFICATION_ID + downloadId.toInt(), notification)
    }

    private fun showFailedNotification(downloadId: Long, fileName: String, error: String) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Download Failed")
            .setContentText("$fileName: $error")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(FOREGROUND_NOTIFICATION_ID + downloadId.toInt(), notification)
    }

    private fun cancelNotification(downloadId: Long) {
        notificationManager.cancel(FOREGROUND_NOTIFICATION_ID + downloadId.toInt())
    }

    private fun checkAndStopIfIdle() {
        if (activeDownloaders.isEmpty()) {
            Log.d(TAG, "No active downloads, service can stop")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel all active downloads on service destroy
        activeDownloaders.keys.toList().forEach { cancelDownload(it) }
    }

    companion object {
        const val ACTION_START_DOWNLOAD = "com.filedownloader.START_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.filedownloader.PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.filedownloader.RESUME_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.filedownloader.CANCEL_DOWNLOAD"
        const val ACTION_STOP_SERVICE = "com.filedownloader.STOP_SERVICE"
        const val EXTRA_DOWNLOAD_ID = "download_id"
    }
}
