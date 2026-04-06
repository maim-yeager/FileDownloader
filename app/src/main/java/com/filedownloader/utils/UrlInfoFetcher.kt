package com.filedownloader.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder

private const val TAG = "UrlFetcher"

/**
 * Holds information fetched from a URL before downloading
 */
data class UrlInfo(
    val url: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val extension: String,
    val supportsRange: Boolean,
    val isValid: Boolean,
    val errorMessage: String = ""
)

/**
 * Fetches metadata from a URL to show file info before downloading
 */
class UrlInfoFetcher(private val client: OkHttpClient) {

    /**
     * Fetch file info from URL (makes HEAD request)
     */
    suspend fun fetchInfo(url: String): UrlInfo = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = normalizeUrl(url)

            // Try HEAD request first
            val headRequest = Request.Builder()
                .url(normalizedUrl)
                .head()
                .addHeader("User-Agent", "Mozilla/5.0 FileDownloader/1.0")
                .build()

            val response = try {
                client.newCall(headRequest).execute()
            } catch (e: Exception) {
                // Try with GET if HEAD fails
                val getRequest = Request.Builder()
                    .url(normalizedUrl)
                    .addHeader("Range", "bytes=0-0")
                    .addHeader("User-Agent", "Mozilla/5.0 FileDownloader/1.0")
                    .build()
                client.newCall(getRequest).execute()
            }

            response.use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    return@withContext UrlInfo(
                        url = normalizedUrl,
                        fileName = "",
                        fileSize = -1,
                        mimeType = "",
                        extension = "",
                        supportsRange = false,
                        isValid = false,
                        errorMessage = "Server returned ${resp.code}: ${resp.message}"
                    )
                }

                // Parse content-disposition for filename
                val contentDisposition = resp.header("Content-Disposition") ?: ""
                val contentType = resp.header("Content-Type") ?: ""
                val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                val acceptRanges = resp.header("Accept-Ranges")?.lowercase() == "bytes"
                val contentRange = resp.header("Content-Range")

                // Get total size from content-range if available
                val totalSize = if (contentRange != null) {
                    contentRange.split("/").lastOrNull()?.toLongOrNull() ?: contentLength
                } else contentLength

                // Extract filename
                val fileName = extractFileName(contentDisposition, normalizedUrl, contentType)
                val extension = FileUtils.getExtension(fileName)
                val mimeType = contentType.substringBefore(";").trim()

                // Check if file type is blocked
                if (FileUtils.isBlockedFile(fileName, mimeType)) {
                    return@withContext UrlInfo(
                        url = normalizedUrl,
                        fileName = fileName,
                        fileSize = totalSize,
                        mimeType = mimeType,
                        extension = extension,
                        supportsRange = acceptRanges,
                        isValid = false,
                        errorMessage = "Video and audio downloads are not supported"
                    )
                }

                UrlInfo(
                    url = normalizedUrl,
                    fileName = fileName,
                    fileSize = totalSize,
                    mimeType = mimeType,
                    extension = extension,
                    supportsRange = acceptRanges,
                    isValid = true
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch URL info: ${e.message}", e)
            UrlInfo(
                url = url,
                fileName = "",
                fileSize = -1,
                mimeType = "",
                extension = "",
                supportsRange = false,
                isValid = false,
                errorMessage = when {
                    e.message?.contains("Unable to resolve host") == true -> "No internet connection or invalid URL"
                    e.message?.contains("timeout") == true -> "Connection timed out"
                    e.message?.contains("refused") == true -> "Connection refused by server"
                    else -> e.message ?: "Failed to connect to server"
                }
            )
        }
    }

    /**
     * Extract file name from Content-Disposition header or URL
     */
    private fun extractFileName(contentDisposition: String, url: String, contentType: String): String {
        // Try Content-Disposition: attachment; filename="example.pdf"
        if (contentDisposition.isNotBlank()) {
            val filenameRegex = Regex("""filename\*?=["']?(?:UTF-8'')?([^"'\r\n;]+)["']?""", RegexOption.IGNORE_CASE)
            val match = filenameRegex.find(contentDisposition)
            if (match != null) {
                val name = try {
                    URLDecoder.decode(match.groupValues[1].trim(), "UTF-8")
                } catch (e: Exception) {
                    match.groupValues[1].trim()
                }
                if (name.isNotBlank()) return sanitizeFileName(name)
            }
        }

        // Try to extract from URL
        val fromUrl = FileUtils.getFileNameFromUrl(url)
        if (fromUrl.isNotBlank() && fromUrl != "download") {
            return sanitizeFileName(fromUrl)
        }

        // Generate from MIME type
        val ext = mimeToExtension(contentType)
        return "download_${System.currentTimeMillis()}${if (ext.isNotEmpty()) ".$ext" else ""}"
    }

    /**
     * Sanitize filename to remove dangerous characters
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    /**
     * Map common MIME types to file extensions
     */
    private fun mimeToExtension(mimeType: String): String {
        return when (mimeType.substringBefore(";").trim().lowercase()) {
            "application/pdf" -> "pdf"
            "application/zip" -> "zip"
            "application/x-rar-compressed", "application/vnd.rar" -> "rar"
            "application/vnd.android.package-archive" -> "apk"
            "application/msword" -> "doc"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.ms-excel" -> "xls"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/json" -> "json"
            "text/html" -> "html"
            "text/plain" -> "txt"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> ""
        }
    }

    /**
     * Normalize and validate URL
     */
    private fun normalizeUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        return normalized
    }
}
