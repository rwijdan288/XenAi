package com.xenai.floatingbot.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.app.NotificationCompat
import com.xenai.floatingbot.MainActivity
import com.xenai.floatingbot.R
import com.xenai.floatingbot.api.ApiClient
import com.xenai.floatingbot.api.HistoryItem
import com.xenai.floatingbot.data.AppPreferences
import kotlinx.coroutines.*
import kotlin.math.abs

class FloatingService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "xen_floating_channel"

        const val ACTION_START = "com.xenai.floatingbot.action.START_FLOATING"
        const val ACTION_STOP = "com.xenai.floatingbot.action.STOP_FLOATING"
        const val ACTION_ANALYZE_IMAGE = "com.xenai.floatingbot.action.ANALYZE_IMAGE"
        const val ACTION_SHOW_ERROR = "com.xenai.floatingbot.action.SHOW_ERROR"

        const val EXTRA_IMAGE_BASE64 = "extra_image_base64"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"

        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: AppPreferences

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var tvPanelStatus: TextView? = null
    private var tvPanelAnswer: TextView? = null
    private var pbPanelLoading: ProgressBar? = null
    private var btnReanalyze: Button? = null
    private var btnCopyAnswer: Button? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentLastAnswer = ""
    private var screenWidth = 1080
    private var screenHeight = 1920

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = AppPreferences(this)
        computeScreenSize()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildForegroundNotification())
                isRunning = true
                showFloatingBubble()
            }
            ACTION_ANALYZE_IMAGE -> {
                val imageBase64 = intent.getStringExtra(EXTRA_IMAGE_BASE64)
                if (!imageBase64.isNullOrEmpty()) {
                    performVisionAnalysis(imageBase64)
                }
            }
            ACTION_SHOW_ERROR -> {
                val errorMsg = intent.getStringExtra(EXTRA_ERROR_MESSAGE) ?: "Terjadi kesalahan."
                showPanelError(errorMsg)
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun computeScreenSize() {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingBubble() {
        if (bubbleView != null) return

        val bubbleSizePx = dpToPx(58)
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - bubbleSizePx - dpToPx(16)
            y = screenHeight / 3
        }

        val bubbleContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#6C5CE7"), Color.parseColor("#0984E3"))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                setStroke(dpToPx(2), Color.WHITE)
            }
            elevation = dpToPx(8).toFloat()
        }

        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_xen_logo)
            val padding = dpToPx(10)
            setPadding(padding, padding, padding, padding)
        }
        bubbleContainer.addView(iconView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        bubbleView = bubbleContainer

        bubbleContainer.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isLongClick = false
            private val longPressRunnable = Runnable {
                isLongClick = true
                Toast.makeText(this@FloatingService, "Xen AI Bubble ditutup", Toast.LENGTH_SHORT).show()
                stopSelf()
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isLongClick = false
                        initialX = bubbleParams?.x ?: 0
                        initialY = bubbleParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        mainHandler.postDelayed(longPressRunnable, 750)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            mainHandler.removeCallbacks(longPressRunnable)
                        }
                        bubbleParams?.x = (initialX + dx).toInt()
                        bubbleParams?.y = (initialY + dy).toInt()
                        bubbleView?.let { windowManager.updateViewLayout(it, bubbleParams) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        if (isLongClick) return true

                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY

                        if (abs(dx) < 15 && abs(dy) < 15) {
                            onBubbleClicked()
                        } else {
                            snapToEdge()
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(bubbleView, bubbleParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun snapToEdge() {
        val currentX = bubbleParams?.x ?: 0
        val targetX = if (currentX + dpToPx(30) < screenWidth / 2) {
            dpToPx(8)
        } else {
            screenWidth - dpToPx(66)
        }
        bubbleParams?.x = targetX
        bubbleView?.let { windowManager.updateViewLayout(it, bubbleParams) }
    }

    private fun onBubbleClicked() {
        showResultPanel(
            status = "Mengambil Tangkapan Layar...",
            content = "Meminta frame layar untuk dianalisis oleh AI...",
            isLoading = true
        )

        val captureIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_CAPTURE
        }
        startService(captureIntent)
    }

    private fun showResultPanel(status: String, content: String, isLoading: Boolean) {
        if (panelView == null) {
            createPanelLayout()
        }

        tvPanelStatus?.text = status
        tvPanelAnswer?.text = content
        pbPanelLoading?.visibility = if (isLoading) View.VISIBLE else View.GONE
        btnReanalyze?.isEnabled = !isLoading
        btnCopyAnswer?.isEnabled = !isLoading && content.isNotEmpty()

        panelView?.visibility = View.VISIBLE
    }

    private fun createPanelLayout() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val panelWidth = (screenWidth * 0.88).toInt().coerceAtMost(dpToPx(420))

        panelParams = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val rootCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dpToPx(18).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#E2E8F0"))
            }
            elevation = dpToPx(16).toFloat()
            val pad = dpToPx(16)
            setPadding(pad, pad, pad, pad)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logoIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_xen_logo)
        }
        headerRow.addView(logoIcon, LinearLayout.LayoutParams(dpToPx(28), dpToPx(28)))

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(10), 0, 0, 0)
        }
        val tvTitle = TextView(this).apply {
            text = "Xen AI Vision"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
        }
        tvPanelStatus = TextView(this).apply {
            text = "Siap"
            textSize = 11f
            setTextColor(Color.parseColor("#6C5CE7"))
        }
        titleContainer.addView(tvTitle)
        titleContainer.addView(tvPanelStatus)
        headerRow.addView(titleContainer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val btnClose = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            setOnClickListener { hideResultPanel() }
        }
        headerRow.addView(btnClose)
        rootCard.addView(headerRow)

        pbPanelLoading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        rootCard.addView(pbPanelLoading, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(6)
        ).apply {
            topMargin = dpToPx(10)
            bottomMargin = dpToPx(6)
        })

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(220)
            ).apply {
                topMargin = dpToPx(8)
                bottomMargin = dpToPx(12)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F8FAFC"))
                cornerRadius = dpToPx(12).toFloat()
            }
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        }

        tvPanelAnswer = TextView(this).apply {
            text = "Ketuk bubble Xen AI untuk mulai menganalisis layar."
            textSize = 13.5f
            lineSpacingMultiplier = 1.25f
            setTextColor(Color.parseColor("#1E293B"))
            setTextIsSelectable(true)
        }
        scrollView.addView(tvPanelAnswer)
        rootCard.addView(scrollView)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        btnCopyAnswer = Button(this).apply {
            text = "Salin"
            textSize = 12f
            setTextColor(Color.parseColor("#0984E3"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFF8FF"))
                cornerRadius = dpToPx(8).toFloat()
            }
            setOnClickListener { copyAnswerToClipboard() }
        }
        actionsRow.addView(btnCopyAnswer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dpToPx(38)
        ).apply { rightMargin = dpToPx(8) })

        btnReanalyze = Button(this).apply {
            text = "Analisis Ulang"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                colors = intArrayOf(Color.parseColor("#6C5CE7"), Color.parseColor("#0984E3"))
                cornerRadius = dpToPx(8).toFloat()
            }
            setOnClickListener { onBubbleClicked() }
        }
        actionsRow.addView(btnReanalyze, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dpToPx(38)
        ))

        rootCard.addView(actionsRow)
        panelView = rootCard

        try {
            windowManager.addView(panelView, panelParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hideResultPanel() {
        panelView?.visibility = View.GONE
    }

    private fun copyAnswerToClipboard() {
        if (currentLastAnswer.isNotEmpty()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Xen AI Answer", currentLastAnswer)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Jawaban disalin ke clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performVisionAnalysis(imageBase64: String) {
        showResultPanel(
            status = "Sedang Menganalisis Gambar...",
            content = "Mengirim tangkapan layar ke model Gemini Vision...",
            isLoading = true
        )

        serviceScope.launch {
            val baseUrl = prefs.backendUrl
            val instruction = prefs.customInstruction

            val result = ApiClient.analyzeScreen(
                baseUrl = baseUrl,
                imageBase64 = imageBase64,
                instruction = instruction
            )

            withContext(Dispatchers.Main) {
                result.onSuccess { response ->
                    val answerText = response.answer ?: "Tidak ada jawaban yang dihasilkan."
                    currentLastAnswer = answerText
                    showResultPanel(
                        status = "Analisis Selesai (${response.model ?: "Gemini"})",
                        content = answerText,
                        isLoading = false
                    )

                    prefs.addHistoryItem(
                        HistoryItem(
                            promptSnippet = instruction.take(60),
                            answer = answerText,
                            isSuccess = true
                        )
                    )
                }.onFailure { error ->
                    val errorMsg = error.message ?: "Gagal menganalisis layar."
                    showPanelError(errorMsg)

                    prefs.addHistoryItem(
                        HistoryItem(
                            promptSnippet = instruction.take(60),
                            answer = "Error: $errorMsg",
                            isSuccess = false
                        )
                    )
                }
            }
        }
    }

    private fun showPanelError(errorMessage: String) {
        showResultPanel(
            status = "Gagal Menganalisis",
            content = "Terjadi kesalahan:\n$errorMessage\n\nTips:\n- Pastikan backend berjalan dan URL benar.\n- Periksa koneksi internet / izin overlay.",
            isLoading = false
        )
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        isRunning = false

        bubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            bubbleView = null
        }

        panelView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            panelView = null
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name_floating),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc_floating)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Xen AI Floating Bot Aktif")
            .setContentText("Ketuk bubble di layar untuk menganalisis pertanyaan/soal.")
            .setSmallIcon(R.drawable.ic_xen_logo)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}