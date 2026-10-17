package com.xenai.floatingbot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.xenai.floatingbot.api.ApiClient
import com.xenai.floatingbot.api.HistoryItem
import com.xenai.floatingbot.data.AppPreferences
import com.xenai.floatingbot.service.FloatingService
import com.xenai.floatingbot.service.ScreenCaptureService
import com.xenai.floatingbot.ui.screens.HomeScreen
import com.xenai.floatingbot.ui.theme.XenAiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var isOverlayGranted by mutableStateOf(false)
    private var isCaptureGranted by mutableStateOf(false)
    private var isBubbleRunning by mutableStateOf(false)

    private var backendStatus by mutableStateOf("Memeriksa koneksi...")
    private var backendLatency by mutableStateOf(0L)
    private var backendUrl by mutableStateOf("")
    private var customInstruction by mutableStateOf("")
    private var historyList = mutableStateListOf<HistoryItem>()

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkOverlayPermission()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            isCaptureGranted = true

            val captureServiceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_INIT
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(captureServiceIntent)
            } else {
                startService(captureServiceIntent)
            }
            Toast.makeText(this, "Izin Tangkapan Layar berhasil diaktifkan", Toast.LENGTH_SHORT).show()
        } else {
            isCaptureGranted = false
            Toast.makeText(this, "Izin Tangkapan Layar ditolak", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = AppPreferences(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        backendUrl = prefs.backendUrl
        customInstruction = prefs.customInstruction
        refreshHistory()

        checkOverlayPermission()
        checkBackendHealth()

        setContent {
            XenAiTheme {
                HomeScreen(
                    isOverlayGranted = isOverlayGranted,
                    isCaptureGranted = isCaptureGranted,
                    isBubbleRunning = isBubbleRunning,
                    backendStatus = backendStatus,
                    backendLatency = backendLatency,
                    backendUrl = backendUrl,
                    customInstruction = customInstruction,
                    historyList = historyList,
                    onRequestOverlayPermission = { requestOverlayPermission() },
                    onRequestCapturePermission = { requestScreenCapturePermission() },
                    onToggleBubble = { toggleFloatingBubble() },
                    onCheckBackendConnection = { checkBackendHealth() },
                    onSaveBackendSettings = { newUrl, newInstruction ->
                        prefs.backendUrl = newUrl
                        prefs.customInstruction = newInstruction
                        backendUrl = newUrl
                        customInstruction = newInstruction
                        checkBackendHealth()
                    },
                    onClearHistory = {
                        prefs.clearHistory()
                        refreshHistory()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkOverlayPermission()
        isBubbleRunning = FloatingService.isRunning
        refreshHistory()
    }

    private fun checkOverlayPermission() {
        isOverlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            isOverlayGranted = true
        }
    }

    private fun requestScreenCapturePermission() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun toggleFloatingBubble() {
        if (!isBubbleRunning) {
            if (!isOverlayGranted) {
                Toast.makeText(this, "Harap berikan Izin Overlay terlebih dahulu.", Toast.LENGTH_LONG).show()
                requestOverlayPermission()
                return
            }

            if (!isCaptureGranted || !ScreenCaptureService.isRunning) {
                Toast.makeText(this, "Harap berikan Izin Tangkapan Layar terlebih dahulu.", Toast.LENGTH_LONG).show()
                requestScreenCapturePermission()
                return
            }

            val serviceIntent = Intent(this, FloatingService::class.java).apply {
                action = FloatingService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            isBubbleRunning = true
            Toast.makeText(this, "Floating Bubble Xen AI aktif di layar", Toast.LENGTH_SHORT).show()
        } else {
            val serviceIntent = Intent(this, FloatingService::class.java).apply {
                action = FloatingService.ACTION_STOP
            }
            startService(serviceIntent)
            isBubbleRunning = false
            Toast.makeText(this, "Floating Bubble dihentikan", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkBackendHealth() {
        backendStatus = "Menghubungi server..."
        backendLatency = 0L

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            val result = ApiClient.checkHealth(backendUrl)
            val duration = System.currentTimeMillis() - startTime

            withContext(Dispatchers.Main) {
                result.onSuccess { response ->
                    backendLatency = duration
                    backendStatus = if (response.apiKeyConfigured) {
                        "Online (${response.model ?: "Gemini"})"
                    } else {
                        "Online (API Key belum dikonfigurasi)"
                    }
                }.onFailure { error ->
                    backendLatency = 0L
                    backendStatus = "Offline: ${error.message ?: "Tidak dapat terhubung"}"
                }
            }
        }
    }

    private fun refreshHistory() {
        historyList.clear()
        historyList.addAll(prefs.getHistory())
    }
}