package com.filedownloader.ui.activities

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.filedownloader.R
import com.filedownloader.data.models.DownloadItem
import com.filedownloader.data.models.DownloadStatus
import com.filedownloader.databinding.ActivityMainBinding
import com.filedownloader.service.DownloadService
import com.filedownloader.ui.adapters.DownloadItemListener
import com.filedownloader.ui.adapters.DownloadsAdapter
import com.filedownloader.ui.viewmodels.MainViewModel
import com.filedownloader.ui.viewmodels.UiState
import com.filedownloader.utils.FileUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main activity - URL input, download list, controls
 */
class MainActivity : AppCompatActivity(), DownloadItemListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: DownloadsAdapter

    // Service binding
    private var downloadService: DownloadService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as DownloadService.DownloadBinder
            downloadService = b.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            downloadService = null
            serviceBound = false
        }
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Storage permission needed for downloads", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
        requestPermissions()
        startDownloadService()
        handleSharedIntent()
    }

    private fun setupRecyclerView() {
        adapter = DownloadsAdapter(this)
        binding.rvDownloads.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(false)
        }
    }

    private fun setupClickListeners() {
        // Analyze URL button
        binding.btnAnalyze.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isBlank()) {
                binding.tilUrl.error = "Please enter a valid URL"
                return@setOnClickListener
            }
            binding.tilUrl.error = null
            viewModel.analyzeUrl(url)
        }

        // Download button (shown after URL analysis)
        binding.btnDownload.setOnClickListener {
            val state = viewModel.uiState.value
            if (state is UiState.UrlInfoLoaded) {
                startDownload(state.info)
            }
        }

        // Clear URL
        binding.btnClearUrl.setOnClickListener {
            binding.etUrl.setText("")
            viewModel.resetState()
            hideFileInfo()
        }

        // File manager button
        binding.fabFileManager.setOnClickListener {
            startActivity(Intent(this, FileManagerActivity::class.java))
        }

        // Thread count slider
        binding.sliderThreads.addOnChangeListener { _, value, _ ->
            val count = value.toInt()
            viewModel.setThreadCount(count)
            binding.tvThreadCount.text = "$count threads"
        }
    }

    private fun observeViewModel() {
        // Observe UI state changes
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is UiState.Idle -> {
                        binding.progressAnalyze.visibility = View.GONE
                        binding.btnAnalyze.isEnabled = true
                    }
                    is UiState.Loading -> {
                        binding.progressAnalyze.visibility = View.VISIBLE
                        binding.btnAnalyze.isEnabled = false
                        hideFileInfo()
                    }
                    is UiState.UrlInfoLoaded -> {
                        binding.progressAnalyze.visibility = View.GONE
                        binding.btnAnalyze.isEnabled = true
                        showFileInfo(state.info)
                    }
                    is UiState.Error -> {
                        binding.progressAnalyze.visibility = View.GONE
                        binding.btnAnalyze.isEnabled = true
                        hideFileInfo()
                        binding.tilUrl.error = state.message
                    }
                }
            }
        }

        // Observe downloads list
        lifecycleScope.launch {
            viewModel.allDownloads.collectLatest { downloads ->
                adapter.submitList(downloads)

                // Show/hide empty state
                if (downloads.isEmpty()) {
                    binding.layoutEmpty.visibility = View.VISIBLE
                    binding.rvDownloads.visibility = View.GONE
                } else {
                    binding.layoutEmpty.visibility = View.GONE
                    binding.rvDownloads.visibility = View.VISIBLE
                }
            }
        }

        // Observe thread count
        lifecycleScope.launch {
            viewModel.threadCount.collectLatest { count ->
                binding.sliderThreads.value = count.toFloat()
                binding.tvThreadCount.text = "$count threads"
            }
        }
    }

    private fun showFileInfo(info: com.filedownloader.utils.UrlInfo) {
        if (!info.isValid) {
            binding.tilUrl.error = info.errorMessage
            hideFileInfo()
            return
        }

        binding.cardFileInfo.visibility = View.VISIBLE
        val anim = AnimationUtils.loadAnimation(this, R.anim.slide_in_top)
        binding.cardFileInfo.startAnimation(anim)

        binding.tvFileInfoName.text = info.fileName
        binding.tvFileInfoSize.text = FileUtils.formatFileSize(info.fileSize)
        binding.tvFileInfoType.text = info.extension.uppercase().ifBlank { "Unknown" }
        binding.tvFileInfoIcon.text = FileUtils.getFileIcon(info.extension)
        binding.tvRangeSupport.text = if (info.supportsRange) "✓ Multi-thread supported" else "⚠ Single-thread only"
        binding.tvRangeSupport.setTextColor(
            ContextCompat.getColor(this, if (info.supportsRange) R.color.status_completed else R.color.status_paused)
        )

        binding.btnDownload.visibility = View.VISIBLE
    }

    private fun hideFileInfo() {
        binding.cardFileInfo.visibility = View.GONE
        binding.btnDownload.visibility = View.GONE
    }

    private fun startDownload(info: com.filedownloader.utils.UrlInfo) {
        lifecycleScope.launch {
            viewModel.addDownload(info).collect { downloadId ->
                if (downloadId > 0) {
                    // Start download via service
                    val intent = Intent(this@MainActivity, DownloadService::class.java).apply {
                        action = DownloadService.ACTION_START_DOWNLOAD
                        putExtra(DownloadService.EXTRA_DOWNLOAD_ID, downloadId)
                    }
                    startService(intent)

                    // Clear URL input
                    binding.etUrl.setText("")
                    viewModel.resetState()
                    hideFileInfo()

                    Toast.makeText(this@MainActivity, "Download started: ${info.fileName}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to add download", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // DownloadItemListener implementations
    override fun onPause(item: DownloadItem) {
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_PAUSE_DOWNLOAD
            putExtra(DownloadService.EXTRA_DOWNLOAD_ID, item.id)
        }
        startService(intent)
    }

    override fun onResume(item: DownloadItem) {
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_RESUME_DOWNLOAD
            putExtra(DownloadService.EXTRA_DOWNLOAD_ID, item.id)
        }
        startService(intent)
    }

    override fun onCancel(item: DownloadItem) {
        AlertDialog.Builder(this)
            .setTitle("Cancel Download")
            .setMessage("Cancel downloading ${item.fileName}?")
            .setPositiveButton("Cancel Download") { _, _ ->
                val intent = Intent(this, DownloadService::class.java).apply {
                    action = DownloadService.ACTION_CANCEL_DOWNLOAD
                    putExtra(DownloadService.EXTRA_DOWNLOAD_ID, item.id)
                }
                startService(intent)
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    override fun onDelete(item: DownloadItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Download")
            .setMessage("Delete ${item.fileName}?")
            .setPositiveButton("Delete Record Only") { _, _ ->
                viewModel.deleteDownload(item, deleteFile = false)
            }
            .setNeutralButton("Delete File Too") { _, _ ->
                viewModel.deleteDownload(item, deleteFile = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onOpen(item: DownloadItem) {
        if (!FileUtils.fileExists(item.filePath)) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val file = java.io.File(item.filePath)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRetry(item: DownloadItem) {
        lifecycleScope.launch {
            val intent = Intent(this@MainActivity, DownloadService::class.java).apply {
                action = DownloadService.ACTION_START_DOWNLOAD
                putExtra(DownloadService.EXTRA_DOWNLOAD_ID, item.id)
            }
            startService(intent)
            Toast.makeText(this@MainActivity, "Retrying: ${item.fileName}", Toast.LENGTH_SHORT).show()
        }
    }

    // Handle URLs shared from other apps
    private fun handleSharedIntent() {
        if (intent?.action == Intent.ACTION_SEND) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                binding.etUrl.setText(sharedText)
                viewModel.analyzeUrl(sharedText)
            }
        }
    }

    private fun startDownloadService() {
        val serviceIntent = Intent(this, DownloadService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_history -> {
                AlertDialog.Builder(this)
                    .setTitle("Clear History")
                    .setMessage("Remove all completed downloads from history?")
                    .setPositiveButton("Clear") { _, _ -> viewModel.clearHistory() }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            R.id.action_file_manager -> {
                startActivity(Intent(this, FileManagerActivity::class.java))
                true
            }
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMessage("Max download threads: ${viewModel.threadCount.value}\n\nUse the slider on the main screen to adjust thread count (1-8).")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (!serviceBound) startDownloadService()
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
