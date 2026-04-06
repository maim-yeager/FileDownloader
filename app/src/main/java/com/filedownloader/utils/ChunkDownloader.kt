package com.filedownloader.utils

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

private const val TAG = "ChunkDownloader"

/**
 * Represents a single download chunk (byte range)
 */
data class DownloadChunk(
    val index: Int,
    val startByte: Long,
    val endByte: Long,
    val tempFile: File
)

/**
 * Callback interface for download progress updates
 */
interface DownloadProgressCallback {
    fun onProgress(downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long)
    fun onCompleted()
    fun onFailed(error: String)
    fun onPaused()
}

/**
 * High-performance multi-threaded chunk downloader.
 *
 * How it works:
 * 1. First makes a HEAD request to get file size and check Range support
 * 2. If server supports Range headers, splits file into N chunks
 * 3. Downloads each chunk in parallel using coroutines
 * 4. Merges all chunks into final file
 * 5. Falls back to single-thread if Range not supported
 */
class ChunkDownloader(
    private val client: OkHttpClient,
    private val url: String,
    private val destFile: File,
    private val threadCount: Int = 4,
    private val callback: DownloadProgressCallback
) {
    // Atomic flags for thread-safe pause/cancel control
    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    // Track total downloaded bytes across all threads
    private val totalDownloaded = AtomicLong(0L)

    // Coroutine scope for chunk download jobs
    private var downloadScope: CoroutineScope? = null

    // File metadata
    var totalSize: Long = -1L
    var supportsRange: Boolean = false

    /**
     * Start the download. Returns true if completed successfully.
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Step 1: Probe server for file info
            val (size, rangeSupport) = probeServer()
            totalSize = size
            supportsRange = rangeSupport

            Log.d(TAG, "File size: $totalSize, Range support: $supportsRange")

            return@withContext if (supportsRange && totalSize > 0 && threadCount > 1) {
                downloadMultiThreaded()
            } else {
                downloadSingleThread()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            callback.onFailed(e.message ?: "Unknown error")
            false
        }
    }

    /**
     * Pause the download (only works with Range-supported downloads)
     */
    fun pause() {
        isPaused.set(true)
        callback.onPaused()
    }

    /**
     * Resume a paused download
     */
    fun resume() {
        isPaused.set(false)
    }

    /**
     * Cancel the download entirely
     */
    fun cancel() {
        isCancelled.set(true)
        isPaused.set(false)
        downloadScope?.cancel()
    }

    /**
     * Probe server to get file size and check Range support
     */
    private fun probeServer(): Pair<Long, Boolean> {
        val request = Request.Builder()
            .url(url)
            .head() // HEAD request - no body, just headers
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Try GET if HEAD fails
                return probeServerWithGet()
            }

            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val acceptRanges = response.header("Accept-Ranges")
            val rangeSupport = acceptRanges?.lowercase() == "bytes"

            return Pair(contentLength, rangeSupport)
        }
    }

    /**
     * Fallback: probe using GET with partial range request
     */
    private fun probeServerWithGet(): Pair<Long, Boolean> {
        val request = Request.Builder()
            .url(url)
            .addHeader("Range", "bytes=0-1") // Request only first 2 bytes
            .build()

        client.newCall(request).execute().use { response ->
            val rangeSupport = response.code == 206 // 206 = Partial Content
            val contentRange = response.header("Content-Range") // e.g. "bytes 0-1/12345"
            val size = contentRange?.split("/")?.lastOrNull()?.toLongOrNull() ?: -1L

            return Pair(size, rangeSupport)
        }
    }

    /**
     * Multi-threaded download using byte range requests
     */
    private suspend fun downloadMultiThreaded(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting multi-threaded download with $threadCount threads")

        // Create temp directory for chunks
        val tempDir = File(destFile.parent, "${destFile.name}.parts")
        tempDir.mkdirs()

        // Calculate chunk boundaries
        val chunks = calculateChunks(tempDir)

        totalDownloaded.set(0L)
        var speedTimer = System.currentTimeMillis()
        var lastDownloaded = 0L

        downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        try {
            // Launch all chunk downloads in parallel
            val jobs = chunks.map { chunk ->
                downloadScope!!.async {
                    downloadChunk(chunk)
                }
            }

            // Monitor progress while chunks download
            val progressJob = downloadScope!!.launch {
                while (jobs.any { it.isActive }) {
                    val now = System.currentTimeMillis()
                    val elapsed = now - speedTimer

                    if (elapsed >= 500) { // Update every 500ms
                        val current = totalDownloaded.get()
                        val speed = ((current - lastDownloaded) * 1000) / elapsed
                        lastDownloaded = current
                        speedTimer = now

                        callback.onProgress(current, totalSize, speed)
                    }

                    // Handle pause
                    while (isPaused.get() && !isCancelled.get()) {
                        delay(100)
                    }

                    if (isCancelled.get()) return@launch

                    delay(100)
                }
            }

            // Wait for all chunks or cancellation
            val results = jobs.map { it.await() }
            progressJob.cancel()

            if (isCancelled.get()) {
                return@withContext false
            }

            if (results.all { it }) {
                // All chunks downloaded - merge them
                Log.d(TAG, "All chunks done, merging...")
                mergeChunks(chunks, destFile)
                tempDir.deleteRecursively()

                callback.onCompleted()
                return@withContext true
            } else {
                val failedCount = results.count { !it }
                callback.onFailed("$failedCount chunk(s) failed to download")
                return@withContext false
            }

        } finally {
            downloadScope = null
        }
    }

    /**
     * Download a single chunk with retry logic
     */
    private suspend fun downloadChunk(chunk: DownloadChunk): Boolean = withContext(Dispatchers.IO) {
        val maxRetries = 3
        var attempt = 0

        while (attempt < maxRetries) {
            if (isCancelled.get()) return@withContext false

            try {
                // Check if chunk already partially downloaded (resume support)
                val startByte = if (chunk.tempFile.exists()) {
                    chunk.startByte + chunk.tempFile.length()
                } else {
                    chunk.startByte
                }

                if (startByte > chunk.endByte) {
                    // Chunk already complete
                    totalDownloaded.addAndGet(chunk.endByte - chunk.startByte + 1)
                    return@withContext true
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=$startByte-${chunk.endByte}")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.code != 206 && response.code != 200) {
                        throw IOException("Server returned ${response.code} for chunk ${chunk.index}")
                    }

                    val body = response.body ?: throw IOException("Empty response body for chunk ${chunk.index}")
                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(chunk.tempFile, chunk.tempFile.exists())
                    val buffer = ByteArray(8 * 1024) // 8KB buffer

                    outputStream.use { out ->
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (isCancelled.get()) return@withContext false

                            // Handle pause - wait until resumed
                            while (isPaused.get() && !isCancelled.get()) {
                                Thread.sleep(50)
                            }

                            out.write(buffer, 0, bytesRead)
                            totalDownloaded.addAndGet(bytesRead.toLong())
                        }
                    }
                }

                return@withContext true // Chunk downloaded successfully

            } catch (e: Exception) {
                attempt++
                Log.w(TAG, "Chunk ${chunk.index} attempt $attempt failed: ${e.message}")
                if (attempt < maxRetries) {
                    delay(1000L * attempt) // Exponential backoff
                }
            }
        }

        return@withContext false // All retries exhausted
    }

    /**
     * Single-threaded fallback download (no Range support)
     */
    private suspend fun downloadSingleThread(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting single-thread download")

        val request = Request.Builder()
            .url(url)
            .build()

        var speedTimer = System.currentTimeMillis()
        var lastDownloaded = 0L
        totalDownloaded.set(0L)

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    callback.onFailed("Server error: ${response.code}")
                    return@withContext false
                }

                // Get content length if available
                if (totalSize == -1L) {
                    totalSize = response.header("Content-Length")?.toLongOrNull() ?: -1L
                }

                val body = response.body ?: run {
                    callback.onFailed("Empty response")
                    return@withContext false
                }

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(destFile)
                val buffer = ByteArray(8 * 1024)

                outputStream.use { out ->
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled.get()) return@withContext false

                        while (isPaused.get() && !isCancelled.get()) {
                            Thread.sleep(50)
                        }

                        out.write(buffer, 0, bytesRead)
                        totalDownloaded.addAndGet(bytesRead.toLong())

                        // Calculate and report speed every 500ms
                        val now = System.currentTimeMillis()
                        val elapsed = now - speedTimer
                        if (elapsed >= 500) {
                            val current = totalDownloaded.get()
                            val speed = ((current - lastDownloaded) * 1000) / elapsed
                            lastDownloaded = current
                            speedTimer = now
                            callback.onProgress(current, totalSize, speed)
                        }
                    }
                }
            }

            callback.onCompleted()
            true

        } catch (e: Exception) {
            if (!isCancelled.get()) {
                callback.onFailed(e.message ?: "Download failed")
            }
            false
        }
    }

    /**
     * Calculate byte ranges for each chunk
     */
    private fun calculateChunks(tempDir: File): List<DownloadChunk> {
        val effectiveThreads = min(threadCount, 8) // Cap at 8 threads
        val chunkSize = totalSize / effectiveThreads

        return (0 until effectiveThreads).map { i ->
            val start = i * chunkSize
            val end = if (i == effectiveThreads - 1) totalSize - 1 else (start + chunkSize - 1)
            DownloadChunk(
                index = i,
                startByte = start,
                endByte = end,
                tempFile = File(tempDir, "chunk_$i.tmp")
            )
        }
    }

    /**
     * Merge all downloaded chunks into final output file
     */
    private fun mergeChunks(chunks: List<DownloadChunk>, output: File) {
        Log.d(TAG, "Merging ${chunks.size} chunks into ${output.name}")

        FileOutputStream(output).use { fos ->
            chunks.sortedBy { it.index }.forEach { chunk ->
                FileInputStream(chunk.tempFile).use { fis ->
                    val buffer = ByteArray(64 * 1024) // 64KB merge buffer
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                    }
                }
                chunk.tempFile.delete()
            }
        }

        Log.d(TAG, "Merge complete: ${output.name} (${output.length()} bytes)")
    }
}
