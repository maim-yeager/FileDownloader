package com.filedownloader.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a download item stored in the database.
 * Each download has a unique ID, URL, file info, and status tracking.
 */
@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // URL of the file to download
    val url: String,

    // Detected file name from URL or Content-Disposition header
    val fileName: String,

    // File extension (pdf, zip, apk, etc.)
    val fileExtension: String,

    // Total file size in bytes (-1 if unknown)
    val totalSize: Long = -1L,

    // Downloaded bytes so far
    val downloadedSize: Long = 0L,

    // Download progress percentage (0-100)
    val progress: Int = 0,

    // Current status of the download
    val status: DownloadStatus = DownloadStatus.PENDING,

    // Local file path where file is saved
    val filePath: String = "",

    // MIME type of the file
    val mimeType: String = "",

    // Timestamp when download was added
    val createdAt: Long = System.currentTimeMillis(),

    // Timestamp when download completed
    val completedAt: Long = 0L,

    // Error message if download failed
    val errorMessage: String = "",

    // Download speed in bytes/sec (for display)
    val downloadSpeed: Long = 0L,

    // Number of threads used for this download
    val threadCount: Int = 4,

    // Whether server supports range requests (multi-threading)
    val supportsRange: Boolean = false
)

/**
 * Enum representing all possible download states
 */
enum class DownloadStatus {
    PENDING,      // Added but not started
    QUEUED,       // Waiting in queue
    CONNECTING,   // Connecting to server
    DOWNLOADING,  // Actively downloading
    PAUSED,       // Paused by user
    COMPLETED,    // Successfully completed
    FAILED,       // Failed with error
    CANCELLED     // Cancelled by user
}

/**
 * File type categories - EXCLUDES video and audio
 */
enum class FileCategory(val extensions: List<String>, val icon: String) {
    DOCUMENT(listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt"), "📄"),
    ARCHIVE(listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz"), "🗜️"),
    IMAGE(listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "tiff", "ico"), "🖼️"),
    APK(listOf("apk", "xapk"), "📱"),
    CODE(listOf("html", "htm", "css", "js", "json", "xml", "java", "kt", "py", "cpp", "h"), "💻"),
    EBOOK(listOf("epub", "mobi", "fb2", "azw3"), "📚"),
    DATABASE(listOf("db", "sqlite", "sql", "csv"), "🗃️"),
    OTHER(emptyList(), "📁");

    companion object {
        /**
         * Detect file category from extension
         * Returns null if file is video or audio (blocked)
         */
        fun fromExtension(ext: String): FileCategory? {
            val lower = ext.lowercase()

            // Block video files
            val videoExts = listOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts", "m3u8", "m3u")
            if (lower in videoExts) return null

            // Block audio files
            val audioExts = listOf("mp3", "wav", "aac", "flac", "ogg", "m4a", "wma", "opus", "aiff")
            if (lower in audioExts) return null

            return values().firstOrNull { lower in it.extensions } ?: OTHER
        }
    }
}
