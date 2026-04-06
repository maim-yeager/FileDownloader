package com.filedownloader.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.filedownloader.databinding.ActivityFileManagerBinding
import com.filedownloader.utils.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Activity showing all downloaded files in the app's download folder
 */
class FileManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileManagerBinding
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Downloaded Files"

        loadFiles()
    }

    private fun loadFiles() {
        val dir = FileUtils.getDownloadDirectory(this)
        val files = dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".parts") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.rvFiles.visibility = View.GONE
            return
        }

        binding.layoutEmpty.visibility = View.GONE
        binding.rvFiles.visibility = View.VISIBLE

        val adapter = FileListAdapter(files) { file ->
            openFile(file)
        }
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = adapter

        binding.tvTotalFiles.text = "${files.size} files • ${FileUtils.formatFileSize(files.sumOf { it.length() })}"
    }

    private fun openFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

// Simple adapter for file list
class FileListAdapter(
    private val files: List<File>,
    private val onClick: (File) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<FileListAdapter.FileViewHolder>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FileViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(com.filedownloader.R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(files[position], onClick)
    }

    override fun getItemCount() = files.size

    class FileViewHolder(itemView: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
        fun bind(file: File, onClick: (File) -> Unit) {
            val ext = FileUtils.getExtension(file.name)
            itemView.findViewById<android.widget.TextView>(com.filedownloader.R.id.tvFileIcon).text = FileUtils.getFileIcon(ext)
            itemView.findViewById<android.widget.TextView>(com.filedownloader.R.id.tvFileName).text = file.name
            itemView.findViewById<android.widget.TextView>(com.filedownloader.R.id.tvFileSize).text = FileUtils.formatFileSize(file.length())
            itemView.setOnClickListener { onClick(file) }
        }
    }
}
