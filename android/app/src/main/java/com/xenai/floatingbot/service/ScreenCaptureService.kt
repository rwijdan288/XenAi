package com.xenai.floatingbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.xenai.floatingbot.R
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "xen_capture_channel"

        const val ACTION_INIT = "com.xenai.floatingbot.action.INIT_PROJECTION"
        const val ACTION_CAPTURE = "com.xenai.floatingbot.action.CAPTURE_SCREEN"
        const val ACTION_STOP = "com.xenai.floatingbot.action.STOP_PROJECTION"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        var isRunning = false
            private set
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = DisplayMetrics.DENSITY_DEFAULT

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        computeDisplayMetrics()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_INIT -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (resultCode != 0 && data != null) {
                    startForegroundWithServiceType()
                    initMediaProjection(resultCode, data)
                    isRunning = true
                }
            }
            ACTION_CAPTURE -> {
                captureSingleFrame()
            }
            ACTION_STOP -> {
                stopProjection()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun computeDisplayMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.configuration.densityDpi
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
            screenDensity = displayMetrics.densityDpi
        }
    }

    private fun startForegroundWithServiceType() {
        val notification = buildForegroundNotification("Layanan Tangkapan Layar Aktif", "Siap menganalisis saat bubble ditekan.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            // KRITIKAL: Android 14 mewajibkan registerCallback SEBELUM createVirtualDisplay!
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.i(TAG, "MediaProjection dihentikan oleh sistem")
                    cleanupCaptureResources()
                    mediaProjection = null
                    isRunning = false
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menginisialisasi MediaProjection", e)
        }
    }

    private fun captureSingleFrame() {
        val projection = mediaProjection
        if (projection == null) {
            notifyFloatingServiceError("Izin tangkapan layar belum aktif atau telah kadaluarsa. Silakan aktifkan kembali dari aplikasi.")
            return
        }

        computeDisplayMetrics()

        try {
            cleanupCaptureResources()

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

            virtualDisplay = projection.createVirtualDisplay(
                "XenAiScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                mainHandler
            )

            imageReader?.setOnImageAvailableListener(object : ImageReader.OnImageAvailableListener {
                private var frameCaptured = false

                override fun onImageAvailable(reader: ImageReader?) {
                    if (frameCaptured) return
                    frameCaptured = true

                    val image: Image? = reader?.acquireLatestImage()
                    if (image != null) {
                        try {
                            val bitmap = imageToBitmap(image)
                            if (bitmap != null) {
                                val base64 = bitmapToBase64(bitmap)
                                notifyFloatingServiceCaptured(base64)
                            } else {
                                notifyFloatingServiceError("Gagal mengonversi frame layar ke format gambar.")
                            }
                        } catch (e: Exception) {
                            notifyFloatingServiceError("Error pemrosesan tangkapan layar: ${e.message}")
                        } finally {
                            image.close()
                            cleanupCaptureResources()
                        }
                    } else {
                        cleanupCaptureResources()
                        notifyFloatingServiceError("Gagal memperoleh frame gambar layar.")
                    }
                }
            }, mainHandler)

        } catch (e: Exception) {
            cleanupCaptureResources()
            notifyFloatingServiceError("Gagal membuat VirtualDisplay: ${e.message}")
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val rawBitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        rawBitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
            rawBitmap.recycle()
            cropped
        } else {
            rawBitmap
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val byteArray = outputStream.toByteArray()
        bitmap.recycle()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun notifyFloatingServiceCaptured(imageBase64: String) {
        val intent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_ANALYZE_IMAGE
            putExtra(FloatingService.EXTRA_IMAGE_BASE64, imageBase64)
        }
        startService(intent)
    }

    private fun notifyFloatingServiceError(errorMessage: String) {
        val intent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_SHOW_ERROR
            putExtra(FloatingService.EXTRA_ERROR_MESSAGE, errorMessage)
        }
        startService(intent)
    }

    private fun cleanupCaptureResources() {
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {}
    }

    private fun stopProjection() {
        cleanupCaptureResources()
        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {}
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopProjection()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name_capture),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc_capture)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_xen_logo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}