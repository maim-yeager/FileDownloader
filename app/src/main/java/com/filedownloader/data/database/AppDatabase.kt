package com.filedownloader.data.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.filedownloader.data.models.DownloadItem
import com.filedownloader.data.models.DownloadStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for download operations
 */
@Dao
interface DownloadDao {

    // Insert a new download item, returns generated ID
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DownloadItem): Long

    // Update existing download
    @Update
    suspend fun update(item: DownloadItem)

    // Delete a download record
    @Delete
    suspend fun delete(item: DownloadItem)

    // Delete by ID
    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Get all downloads ordered by creation time (newest first)
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<DownloadItem>>

    // Get download by ID
    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadItem?

    // Get active downloads (downloading or paused)
    @Query("SELECT * FROM downloads WHERE status IN ('DOWNLOADING', 'PAUSED', 'QUEUED', 'CONNECTING')")
    fun getActiveDownloads(): Flow<List<DownloadItem>>

    // Get completed downloads
    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    fun getCompletedDownloads(): Flow<List<DownloadItem>>

    // Update progress of a download
    @Query("UPDATE downloads SET downloadedSize = :downloaded, progress = :progress, downloadSpeed = :speed, status = :status WHERE id = :id")
    suspend fun updateProgress(id: Long, downloaded: Long, progress: Int, speed: Long, status: DownloadStatus)

    // Update status only
    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus)

    // Update file path after download starts
    @Query("UPDATE downloads SET filePath = :path, totalSize = :size, supportsRange = :range WHERE id = :id")
    suspend fun updateFileInfo(id: Long, path: String, size: Long, range: Boolean)

    // Mark download as completed
    @Query("UPDATE downloads SET status = 'COMPLETED', progress = 100, downloadedSize = totalSize, completedAt = :time WHERE id = :id")
    suspend fun markCompleted(id: Long, time: Long = System.currentTimeMillis())

    // Mark download as failed
    @Query("UPDATE downloads SET status = 'FAILED', errorMessage = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    // Clear all completed downloads history
    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    // Get count of active downloads
    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('DOWNLOADING', 'QUEUED', 'CONNECTING')")
    suspend fun getActiveCount(): Int
}

/**
 * Room type converters for custom types
 */
class Converters {
    @TypeConverter
    fun fromStatus(status: DownloadStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)
}

/**
 * Room Database definition
 */
@Database(
    entities = [DownloadItem::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "file_downloader.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
