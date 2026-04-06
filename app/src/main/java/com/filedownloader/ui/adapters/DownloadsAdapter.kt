package com.filedownloader.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.filedownloader.R
import com.filedownloader.data.models.DownloadItem
import com.filedownloader.data.models.DownloadStatus
import com.filedownloader.utils.FileUtils

/**
 * Callback interface for download item actions
 */
interface DownloadItemListener {
    fun onPause(item: DownloadItem)
    fun onResume(item: DownloadItem)
    fun onCancel(item: DownloadItem)
    fun onDelete(item: DownloadItem)
    fun onOpen(item: DownloadItem)
    fun onRetry(item: DownloadItem)
}

/**
 * RecyclerView adapter for displaying download items
 */
class DownloadsAdapter(
    private val listener: DownloadItemListener
) : ListAdapter<DownloadItem, DownloadsAdapter.DownloadViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return DownloadViewHolder(view)
    }

    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        holder.bind(getItem(position), listener)
    }

    class DownloadViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvFileInfo: TextView = itemView.findViewById(R.id.tvFileInfo)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvSpeed: TextView = itemView.findViewById(R.id.tvSpeed)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val tvProgress: TextView = itemView.findViewById(R.id.tvProgress)
        private val ivFileIcon: TextView = itemView.findViewById(R.id.tvFileIcon)
        private val btnAction: ImageButton = itemView.findViewById(R.id.btnAction)
        private val btnCancel: ImageButton = itemView.findViewById(R.id.btnCancel)

        fun bind(item: DownloadItem, listener: DownloadItemListener) {
            // File name and icon
            tvFileName.text = item.fileName.ifBlank { "Unknown file" }
            ivFileIcon.text = FileUtils.getFileIcon(item.fileExtension)

            // File size info
            val sizeText = when {
                item.totalSize > 0 -> FileUtils.formatFileSize(item.totalSize)
                else -> "Unknown size"
            }
            tvFileInfo.text = "${item.fileExtension.uppercase().ifBlank { "FILE" }} • $sizeText"

            // Progress
            val progress = item.progress
            progressBar.progress = progress
            tvProgress.text = "$progress%"

            // Status-specific UI
            when (item.status) {
                DownloadStatus.DOWNLOADING -> {
                    tvStatus.text = "Downloading"
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_downloading))
                    tvSpeed.visibility = View.VISIBLE
                    tvSpeed.text = FileUtils.formatSpeed(item.downloadSpeed)
                    progressBar.visibility = View.VISIBLE
                    tvProgress.visibility = View.VISIBLE
                    btnAction.setImageResource(R.drawable.ic_pause)
                    btnAction.visibility = View.VISIBLE
                    btnCancel.visibility = View.VISIBLE
                    btnAction.setOnClickListener { listener.onPause(item) }
                }
                DownloadStatus.PAUSED -> {
                    tvStatus.text = "Paused"
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_paused))
                    tvSpeed.visibility = View.GONE
                    progressBar.visibility = View.VISIBLE
                    tvProgress.visibility = View.VISIBLE
                    btnAction.setImageResource(R.drawable.ic_play)
                    btnAction.visibility = View.VISIBLE
                    btnCancel.visibility = View.VISIBLE
                    btnAction.setOnClickListener { listener.onResume(item) }
                }
                DownloadStatus.COMPLETED -> {
                    tvStatus.text = "Completed"
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_completed))
                    tvSpeed.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    tvProgress.visibility = View.GONE
                    btnAction.setImageResource(R.drawable.ic_open)
                    btnAction.visibility = View.VISIBLE
                    btnCancel.setImageResource(R.drawable.ic_delete)
                    btnCancel.visibility = View.VISIBLE
                    btnAction.setOnClickListener { listener.onOpen(item) }
                    btnCancel.setOnClickListener { listener.onDelete(item) }
                }
                DownloadStatus.FAILED -> {
                    tvStatus.text = "Failed"
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_failed))
                    tvSpeed.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    tvProgress.visibility = View.GONE
                    btnAction.setImageResource(R.drawable.ic_retry)
                    btnAction.visibility = View.VISIBLE
                    btnCancel.setImageResource(R.drawable.ic_delete)
                    btnCancel.visibility = View.VISIBLE
                    btnAction.setOnClickListener { listener.onRetry(item) }
                    btnCancel.setOnClickListener { listener.onDelete(item) }
                }
                DownloadStatus.QUEUED, DownloadStatus.PENDING -> {
                    tvStatus.text = "Queued"
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_queued))
                    tvSpeed.visibility = View.GONE
                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = true
                    tvProgress.visibility = View.GONE
                    btnAction.visibility = View.GONE
                    btnCancel.setImageResource(R.drawable.ic_cancel)
                    btnCancel.visibility = View.VISIBLE
                    btnCancel.setOnClickListener { listener.onCancel(item) }
                }
                DownloadStatus.CONNECTING -> {
                    tvStatus.text = "Connecting..."
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_queued))
                    tvSpeed.visibility = View.GONE
                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = true
                    tvProgress.visibility = View.GONE
                    btnAction.visibility = View.GONE
                    btnCancel.setImageResource(R.drawable.ic_cancel)
                    btnCancel.visibility = View.VISIBLE
                    btnCancel.setOnClickListener { listener.onCancel(item) }
                }
                DownloadStatus.CANCELLED -> {
                    tvStatus.text = "Cancelled"
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_failed))
                    tvSpeed.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    tvProgress.visibility = View.GONE
                    btnAction.setImageResource(R.drawable.ic_retry)
                    btnAction.visibility = View.VISIBLE
                    btnCancel.setImageResource(R.drawable.ic_delete)
                    btnCancel.visibility = View.VISIBLE
                    btnAction.setOnClickListener { listener.onRetry(item) }
                    btnCancel.setOnClickListener { listener.onDelete(item) }
                }
            }

            // Reset indeterminate for non-queued states
            if (item.status != DownloadStatus.QUEUED && item.status != DownloadStatus.CONNECTING) {
                progressBar.isIndeterminate = false
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(old: DownloadItem, new: DownloadItem) = old.id == new.id
            override fun areContentsTheSame(old: DownloadItem, new: DownloadItem) = old == new
        }
    }
}
