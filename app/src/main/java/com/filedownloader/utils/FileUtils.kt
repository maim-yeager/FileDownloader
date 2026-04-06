package com.filedownloader.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.net.URLDecoder
import java.text.DecimalFormat

/**
 * Utility functions for file operations and formatting
 */
object FileUtils {

    /**
     * Blocked MIME types - video and audio
     */
    private val BLOCKED_MIME_TYPES = setOf(
        "video/mp4", "video/avi", "video/mkv", "video/quicktime",
        "video/x-msvideo", "video/webm", "video/x-matroska",
        "audio/mpeg", "audio/mp3", "audio/wav", "audio/aac",
        "audio/ogg", "audio/flac", "audio/x-m4a", "audio/webm",
        "application/x-mpegURL", "application/vnd.apple.mpegurl"
    )

    private val BLOCKED_EXTENSIONS = setOf(
        "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v",
        "3gp", "ts", "m3u8", "m3u", "mp3", "wav", "aac", "flac",
        "ogg", "m4a", "wma", "opus", "aiff", "alac"
    )

    /**
     * Extract filename from URL
     */
    fun getFileNameFromUrl(url: String): String {
        return try {
            val decoded = URLDecoder.decode(url, "UTF-8")
            val path = Uri.parse(decoded).lastPathSegment ?: "download"
            // Remove query parameters if any got through
            path.substringBefore("?").substringBefore("&").ifBlank { "download" }
        } catch (e: Exception) {
            "download_${System.currentTimeMillis()}"
        }
    }

    /**
     * Extract file extension from filename or URL
     */
    fun getExtension(fileName: String): String {
        return fileName.substringAfterLast(".", "").lowercase().take(10)
    }

    /**
     * Check if a URL/MIME type is blocked (video/audio)
     */
    fun isBlockedFile(url: String, mimeType: String? = null): Boolean {
        // Check MIME type
        if (mimeType != null && BLOCKED_MIME_TYPES.any { mimeType.lowercase().startsWith(it) }) {
            return true
        }
        // Check extension
        val ext = getExtension(getFileNameFromUrl(url))
        return ext in BLOCKED_EXTENSIONS
    }

    /**
     * Get the download directory (public Downloads folder)
     */
    fun getDownloadDirectory(context: Context): File {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appDir = File(dir, "FileDownloader")
        if (!appDir.exists()) appDir.mkdirs()
        return appDir
    }

    /**
     * Generate a unique file path, appending number if file exists
     */
    fun getUniqueFilePath(directory: File, fileName: String): File {
        var file = File(directory, fileName)
        if (!file.exists()) return file

        val name = fileName.substringBeforeLast(".")
        val ext = fileName.substringAfterLast(".", "")

        var counter = 1
        while (file.exists()) {
            file = if (ext.isNotEmpty()) {
                File(directory, "${name}($counter).$ext")
            } else {
                File(directory, "${name}($counter)")
            }
            counter++
        }
        return file
    }

    /**
     * Format file size to human-readable string
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "Unknown"
        val df = DecimalFormat("#.##")
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${df.format(bytes / 1024.0)} KB"
            bytes < 1024 * 1024 * 1024 -> "${df.format(bytes / (1024.0 * 1024))} MB"
            else -> "${df.format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }

    /**
     * Format download speed to human-readable string
     */
    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 KB/s"
        val df = DecimalFormat("#.#")
        return when {
            bytesPerSec < 1024 -> "${bytesPerSec} B/s"
            bytesPerSec < 1024 * 1024 -> "${df.format(bytesPerSec / 1024.0)} KB/s"
            else -> "${df.format(bytesPerSec / (1024.0 * 1024))} MB/s"
        }
    }

    /**
     * Estimate remaining time based on current speed
     */
    fun formatEta(remainingBytes: Long, speedBytesPerSec: Long): String {
        if (speedBytesPerSec <= 0 || remainingBytes <= 0) return "--:--"
        val seconds = remainingBytes / speedBytesPerSec
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    /**
     * Get MIME type icon emoji for display
     */
    fun getFileIcon(extension: String): String {
        return when (extension.lowercase()) {
            "pdf" -> "📄"
            "doc", "docx" -> "📝"
            "xls", "xlsx" -> "📊"
            "ppt", "pptx" -> "📋"
            "zip", "rar", "7z", "tar", "gz" -> "🗜️"
            "apk", "xapk" -> "📱"
            "jpg", "jpeg", "png", "gif", "webp" -> "🖼️"
            "txt", "rtf" -> "📃"
            "epub", "mobi" -> "📚"
            "html", "htm" -> "🌐"
            "json", "xml" -> "🔧"
            "db", "sqlite" -> "🗃️"
            else -> "📁"
        }
    }

    /**
     * Check if a file exists at the given path
     */
    fun fileExists(path: String): Boolean {
        if (path.isBlank()) return false
        return File(path).exists()
    }

    /**
     * Delete a file and its partial download data
     */
    fun deleteFile(filePath: String): Boolean {
        if (filePath.isBlank()) return true
        val file = File(filePath)
        val partsDir = File("${filePath}.parts")
        partsDir.deleteRecursively()
        return if (file.exists()) file.delete() else true
    }
}
