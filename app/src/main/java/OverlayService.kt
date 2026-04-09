package com.example.dynamic

import android.app.Notification
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.animation.ValueAnimator
import com.example.dynamic.R

class OverlayService : Service() {

    companion object {
        @Volatile
        var isRunning: Boolean = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var pillView: LinearLayout
    private lateinit var albumArt: ImageView
    private lateinit var barsContainer: LinearLayout
    private lateinit var waveContainer: LinearLayout
    private lateinit var timerIcon: ImageView
    private lateinit var timerText: TextView
    private lateinit var answerCallBtn: TextView
    private lateinit var rejectCallBtn: TextView
    private var isExpanded = false
    private var currentTitle = ""
    private var currentArtist = ""
    private val PILL_SMALL = 200
    private val PILL_LARGE = 300
    private val PILL_CALL = 400
    private val PILL_EXPANDED = 500
    private val PILL_HEIGHT = 82
    private val PULSE_TIMEOUT_MS = 4000L
    private val PULSE_IN_DURATION_MS = 170L
    private val PULSE_OUT_DURATION_MS = 220L
    private val DEFAULT_ICON_BG_COLOR = Color.parseColor("#202124")
    private val SPOTIFY_PACKAGE = "com.spotify.music"
    private val MEDIA_TARGET_PACKAGES = listOf(
        SPOTIFY_PACKAGE,
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.maps"
    )
    private val CLOCK_PACKAGES = listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage")
    private val CALL_PACKAGES = listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.whatsapp")
    private val barAnimators = mutableListOf<ValueAnimator>()
    private val waveAnimators = mutableListOf<ValueAnimator>()
    private val visibilityHandler = Handler(Looper.getMainLooper())
    private var widthAnimator: ValueAnimator? = null
    private var timerIconAnimator: ValueAnimator? = null
    private var navigationIconAnimator: ValueAnimator? = null
    private var pulseInAnimator: ValueAnimator? = null
    private var pulseOutAnimator: ValueAnimator? = null
    private var pulseAnimationGeneration: Int = 0
    private var isPillHiddenForForegroundApp = false
    private var isTimerActive = false
    private var isNavigationActive = false
    private var isTransferActive = false
    private var isCallActive = false
    private var isMediaActive = false
    private var isPulseEnabled = true
    private var isPulseActive = false
    private var isReceiverRegistered = false
    private var isOverlayAttached = false
    private var activeMediaSourcePackage: String? = null
    private var activeTimerSourcePackage: String? = null
    private var activeNavigationSourcePackage: String? = null
    private var activeTransferSourcePackage: String? = null
    private var activePulseSourcePackage: String? = null
    private var activeCallSourcePackage: String? = null
    private var activeCallKey: String? = null
    private val prefsName = "dynamic_prefs"
    private val keyPulseEnabled = "notification_pulse_enabled"
    private val keyVisualizerMode = "visualizer_mode"
    private var visualizerMode = "BAR"
    private val pulseResetRunnable = Runnable {
        if (!isPulseActive) return@Runnable
        val generation = pulseAnimationGeneration
        playPulseOutAnimation(generation) {
            if (!isPulseActive || generation != pulseAnimationGeneration) return@playPulseOutAnimation
            clearPulseState()
            renderStateAfterTransient()
        }
    }
    private val visibilityCheckRunnable = object : Runnable {
        override fun run() {
            updatePillVisibilityForForegroundApp()
            visibilityHandler.postDelayed(this, 700)
        }
    }

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "MEDIA_UPDATE" -> {
                    activeMediaSourcePackage = intent.getStringExtra("sourcePackage") ?: activeMediaSourcePackage
                    isMediaActive = true
                    currentTitle = intent.getStringExtra("title") ?: ""
                    currentArtist = intent.getStringExtra("artist") ?: ""
                    val art = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("albumArt", Bitmap::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("albumArt")
                    }
                    val appIcon = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("appIcon", Bitmap::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("appIcon")
                    }
                    if (art != null) {
                        setAlbumArtBitmapWithAdaptiveBackground(art)
                    } else if (appIcon != null) {
                        setAlbumArtBitmapWithAdaptiveBackground(appIcon)
                    } else if (!setAlbumArtFromPackage(activeMediaSourcePackage)) {
                        albumArt.setImageResource(R.drawable.ic_dynamic_media)
                        setAlbumArtBackground(DEFAULT_ICON_BG_COLOR)
                    } else {
                        Unit
                    }
                    if (!isTimerActive && !isNavigationActive && !isTransferActive && !isCallActive && !isPulseActive) {
                        showMediaVisualizer()
                        setupCompactContent()
                        animateWidth(PILL_LARGE)
                    }
                }
                "MEDIA_STOPPED" -> {
                    activeMediaSourcePackage = null
                    isMediaActive = false
                    currentTitle = ""
                    currentArtist = ""
                    if (!isTimerActive && !isNavigationActive && !isTransferActive && !isCallActive && !isPulseActive) {
                        stopMediaVisualizerAnimation()
                        albumArt.visibility = View.GONE
                        barsContainer.visibility = View.GONE
                        waveContainer.visibility = View.GONE
                        setupCompactContent()
                        animateWidth(PILL_SMALL)
                    }
                }
                "VISUALIZER_MODE_UPDATE" -> {
                    val mode = intent.getStringExtra("mode")?.uppercase() ?: "BAR"
                    applyVisualizerMode(mode)
                }
                "NAVIGATION_UPDATE" -> {
                    if (isCallActive) return
                    isNavigationActive = true
                    activeNavigationSourcePackage = intent.getStringExtra("sourcePackage")
                    timerIcon.setImageResource(R.drawable.ic_dynamic_media)
                    timerText.text = intent.getStringExtra("text") ?: "Yol tarifi aktif"
                    timerIcon.visibility = View.VISIBLE
                    timerText.visibility = View.VISIBLE
                    stopMediaVisualizerAnimation()
                    stopTimerIconAnimation()
                    setupCompactContent()
                    startNavigationIconAnimation()
                    if (!isPulseActive) animateWidth(PILL_LARGE)
                }
                "NAVIGATION_STOPPED" -> {
                    if (!isNavigationActive) return
                    isNavigationActive = false
                    activeNavigationSourcePackage = null
                    stopNavigationIconAnimation()
                    timerIcon.visibility = View.GONE
                    timerText.visibility = View.GONE
                    if (!isPulseActive) {
                        renderStateAfterTransient()
                    }
                }
                "TIMER_UPDATE", "STOPWATCH_UPDATE", "COUNTDOWN_UPDATE" -> {
                    if (isCallActive) return
                    isTimerActive = true
                    activeTimerSourcePackage = resolveTimerSourcePackage(intent)
                    val isCountdown = intent.action == "COUNTDOWN_UPDATE" ||
                            (intent.getStringExtra("mode") ?: "").equals("COUNTDOWN", ignoreCase = true)
                    timerIcon.setImageResource(R.drawable.ic_dynamic_timer)
                    timerText.text = formatTimerText(intent, isCountdown)
                    timerIcon.visibility = View.VISIBLE
                    timerText.visibility = View.VISIBLE
                    stopMediaVisualizerAnimation()
                    stopNavigationIconAnimation()
                    setupCompactContent()
                    startTimerIconAnimation()
                    if (!isPulseActive) animateWidth(PILL_LARGE)
                }
                "TIMER_STOPPED", "STOPWATCH_STOPPED", "COUNTDOWN_STOPPED" -> {
                    if (isCallActive) return
                    isTimerActive = false
                    activeTimerSourcePackage = null
                    stopTimerIconAnimation()
                    timerIcon.visibility = View.GONE
                    timerText.visibility = View.GONE
                    setupCompactContent()
                    if (isTransferActive) {
                        timerIcon.visibility = View.VISIBLE
                        timerText.visibility = View.VISIBLE
                        startTimerIconAnimation()
                        if (!isPulseActive) animateWidth(PILL_LARGE)
                    } else if (isNavigationActive) {
                        timerIcon.visibility = View.VISIBLE
                        timerText.visibility = View.VISIBLE
                        startNavigationIconAnimation()
                        if (!isPulseActive) animateWidth(PILL_LARGE)
                    } else if (isMediaActive) {
                        showMediaVisualizer()
                        if (!isPulseActive) animateWidth(PILL_LARGE)
                    } else {
                        albumArt.visibility = View.GONE
                        barsContainer.visibility = View.GONE
                        if (!isPulseActive) animateWidth(PILL_SMALL)
                    }
                }
                "TRANSFER_UPDATE" -> {
                    if (isTimerActive || isCallActive) return
                    isTransferActive = true
                    activeTransferSourcePackage = intent.getStringExtra("sourcePackage")
                    val transferType = intent.getStringExtra("transferType") ?: "DOWNLOAD"
                    timerIcon.setImageResource(
                        if (transferType == "UPLOAD") R.drawable.ic_dynamic_upload
                        else R.drawable.ic_dynamic_download
                    )
                    timerText.text = intent.getStringExtra("text") ?: "Aktarim devam ediyor"
                    timerIcon.visibility = View.VISIBLE
                    timerText.visibility = View.VISIBLE
                    stopMediaVisualizerAnimation()
                    stopNavigationIconAnimation()
                    setupCompactContent()
                    startTimerIconAnimation()
                    if (!isPulseActive) animateWidth(PILL_LARGE)
                }
                "TRANSFER_STOPPED" -> {
                    if (!isTransferActive) return
                    isTransferActive = false
                    activeTransferSourcePackage = null
                    stopTimerIconAnimation()
                    timerIcon.setImageResource(R.drawable.ic_dynamic_timer)
                    timerIcon.visibility = View.GONE
                    timerText.visibility = View.GONE
                    setupCompactContent()
                    if (isNavigationActive) {
                        timerIcon.visibility = View.VISIBLE
                        timerText.visibility = View.VISIBLE
                        startNavigationIconAnimation()
                        if (!isPulseActive) animateWidth(PILL_LARGE)
                    } else if (isMediaActive) {
                        showMediaVisualizer()
                        if (!isPulseActive) animateWidth(PILL_LARGE)
                    } else {
                        albumArt.visibility = View.GONE
                        barsContainer.visibility = View.GONE
                        if (!isPulseActive) animateWidth(PILL_SMALL)
                    }
                }
                "PULSE_TOGGLE" -> {
                    val enabled = intent.getBooleanExtra("enabled", true)
                    isPulseEnabled = enabled
                    getSharedPreferences(prefsName, MODE_PRIVATE)
                        .edit()
                        .putBoolean(keyPulseEnabled, enabled)
                        .apply()
                    if (!enabled && isPulseActive) {
                        clearPulseState()
                        renderStateAfterTransient()
                    }
                }
                "PULSE_UPDATE" -> {
                    if (!isPulseEnabled || isCallActive) return
                    val iconBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("appIcon", Bitmap::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("appIcon")
                    }
                    val sourcePackage = intent.getStringExtra("sourcePackage")
                    activePulseSourcePackage = sourcePackage
                    isPulseActive = true
                    pulseAnimationGeneration += 1
                    val generation = pulseAnimationGeneration

                    if (iconBitmap != null) {
                        setAlbumArtBitmapWithAdaptiveBackground(iconBitmap)
                    } else if (!setAlbumArtFromPackage(sourcePackage)) {
                        albumArt.setImageResource(R.drawable.ic_dynamic_media)
                        setAlbumArtBackground(DEFAULT_ICON_BG_COLOR)
                    } else {
                        Unit
                    }
                    albumArt.visibility = View.VISIBLE
                    barsContainer.visibility = View.GONE
                    waveContainer.visibility = View.GONE
                    timerIcon.visibility = View.GONE
                    timerText.visibility = View.GONE
                    setupCompactContent()
                    animateWidth(PILL_LARGE)
                    playPulseInAnimation(generation)
                    performTickVibration(18L)
                    visibilityHandler.removeCallbacks(pulseResetRunnable)
                    visibilityHandler.postDelayed(pulseResetRunnable, PULSE_TIMEOUT_MS)
                }
                "CALL_UPDATE" -> {
                    isCallActive = true
                    activeCallSourcePackage = intent.getStringExtra("sourcePackage")
                    activeCallKey = intent.getStringExtra("callKey")
                    albumArt.setImageResource(R.drawable.ic_dynamic_call)
                    setAlbumArtBackground(Color.parseColor("#D93025"))
                    albumArt.visibility = View.VISIBLE
                    barsContainer.visibility = View.GONE
                    waveContainer.visibility = View.GONE
                    timerIcon.visibility = View.GONE
                    timerText.visibility = View.GONE
                    answerCallBtn.visibility = View.GONE
                    rejectCallBtn.visibility = View.GONE
                    stopMediaVisualizerAnimation()
                    stopTimerIconAnimation()
                    stopNavigationIconAnimation()
                    visibilityHandler.removeCallbacks(pulseResetRunnable)
                    isPulseActive = false
                    setupCompactContent()
                    animateWidth(PILL_LARGE)
                }
                "CALL_STOPPED" -> {
                    if (!isCallActive) return
                    isCallActive = false
                    activeCallSourcePackage = null
                    activeCallKey = null
                    answerCallBtn.visibility = View.GONE
                    rejectCallBtn.visibility = View.GONE
                    albumArt.visibility = View.GONE
                    timerIcon.setImageResource(R.drawable.ic_dynamic_timer)
                    timerIcon.visibility = View.GONE
                    timerText.visibility = View.GONE
                    setupCompactContent()

                    if (isTimerActive) {
                        timerIcon.setImageResource(R.drawable.ic_dynamic_timer)
                        timerIcon.visibility = View.VISIBLE
                        timerText.visibility = View.VISIBLE
                        startTimerIconAnimation()
                        animateWidth(PILL_LARGE)
                    } else if (isNavigationActive) {
                        timerIcon.setImageResource(R.drawable.ic_dynamic_media)
                        timerIcon.visibility = View.VISIBLE
                        timerText.visibility = View.VISIBLE
                        startNavigationIconAnimation()
                        animateWidth(PILL_LARGE)
                    } else if (isTransferActive) {
                        timerIcon.visibility = View.VISIBLE
                        timerText.visibility = View.VISIBLE
                        startTimerIconAnimation()
                        animateWidth(PILL_LARGE)
                    } else if (isMediaActive) {
                        showMediaVisualizer()
                        animateWidth(PILL_LARGE)
                    } else {
                        animateWidth(PILL_SMALL)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        isPulseEnabled = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getBoolean(keyPulseEnabled, true)
        visualizerMode = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(keyVisualizerMode, "BAR")
            ?.uppercase() ?: "BAR"
        isRunning = true
        startForeground(1, createNotification())
        showOverlay()
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction("MEDIA_UPDATE")
                addAction("MEDIA_STOPPED")
                addAction("TIMER_UPDATE")
                addAction("TIMER_STOPPED")
                addAction("STOPWATCH_UPDATE")
                addAction("STOPWATCH_STOPPED")
                addAction("COUNTDOWN_UPDATE")
                addAction("COUNTDOWN_STOPPED")
                addAction("TRANSFER_UPDATE")
                addAction("TRANSFER_STOPPED")
                addAction("CALL_UPDATE")
                addAction("CALL_STOPPED")
                addAction("PULSE_UPDATE")
                addAction("PULSE_TOGGLE")
                addAction("NAVIGATION_UPDATE")
                addAction("NAVIGATION_STOPPED")
                addAction("VISUALIZER_MODE_UPDATE")
            }
            ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true
        }
        startVisibilityMonitor()
        return START_STICKY
    }

    private fun showOverlay() {
        if (isOverlayAttached && ::pillView.isInitialized) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        pillView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 18, 24, 18)
            minimumHeight = PILL_HEIGHT
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 80f
            }
        }

        albumArt = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(48, 48).apply {
                marginEnd = 12
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(DEFAULT_ICON_BG_COLOR)
                cornerRadius = 8f
            }
            visibility = View.GONE
        }

        barsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }

        waveContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }

        repeat(4) { i ->
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(6, 20).apply {
                    marginStart = if (i == 0) 0 else 5
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#1DB954"))
                    cornerRadius = 4f
                }
            }
            barsContainer.addView(bar)
        }

        repeat(3) { i ->
            val wave = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    if (i == 1) 22 else 14,
                    5
                ).apply {
                    marginStart = if (i == 0) 0 else 6
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#1DB954"))
                    cornerRadius = 6f
                }
            }
            waveContainer.addView(wave)
        }

        timerIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(36, 36).apply {
                marginEnd = 12
            }
            setImageResource(R.drawable.ic_dynamic_timer)
            setColorFilter(Color.WHITE)
            visibility = View.GONE
        }

        timerText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            visibility = View.GONE
        }

        rejectCallBtn = TextView(this).apply {
            text = "Reddet"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(14, 6, 14, 6)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#D93025"))
                cornerRadius = 22f
            }
            visibility = View.GONE
            setOnClickListener {
                sendBroadcast(Intent("CALL_COMMAND")
                    .putExtra("command", "REJECT")
                    .putExtra("callKey", activeCallKey))
            }
        }

        answerCallBtn = TextView(this).apply {
            text = "Yanitla"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(14, 6, 14, 6)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#34A853"))
                cornerRadius = 22f
            }
            visibility = View.GONE
            setOnClickListener {
                sendBroadcast(Intent("CALL_COMMAND")
                    .putExtra("command", "ANSWER")
                    .putExtra("callKey", activeCallKey))
            }
        }

        setupCompactContent()

        pillView.setOnClickListener {
            val targetPackage = if (isPulseActive) {
                activePulseSourcePackage ?: SPOTIFY_PACKAGE
            } else if (isCallActive) {
                activeCallSourcePackage ?: CALL_PACKAGES.firstOrNull { isPackageInstalled(it) } ?: SPOTIFY_PACKAGE
            } else if (isTimerActive) {
                activeTimerSourcePackage ?: CLOCK_PACKAGES.firstOrNull { isPackageInstalled(it) } ?: SPOTIFY_PACKAGE
            } else if (isNavigationActive) {
                activeNavigationSourcePackage ?: "com.google.android.apps.maps"
            } else if (isTransferActive) {
                activeTransferSourcePackage
                    ?: MEDIA_TARGET_PACKAGES.firstOrNull { isPackageInstalled(it) }
                    ?: SPOTIFY_PACKAGE
            } else if (isMediaActive) {
                activeMediaSourcePackage
                    ?: MEDIA_TARGET_PACKAGES.firstOrNull { isPackageInstalled(it) }
                    ?: SPOTIFY_PACKAGE
            } else {
                SPOTIFY_PACKAGE
            }
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent?.let { startActivity(it) }
        }

        pillView.setOnLongClickListener {
            performTickVibration(28L)
            when {
                isCallActive || isTimerActive || isNavigationActive || isTransferActive || isMediaActive || isPulseActive -> {
                    sendBroadcast(Intent("PROCESS_COMMAND").apply {
                        putExtra("mode", currentOperationMode())
                        putExtra("sourcePackage", currentOperationPackage())
                        putExtra("callKey", activeCallKey)
                    })
                }
                else -> if (isExpanded) collapseControls() else expandControls()
            }
            true
        }

        val params = WindowManager.LayoutParams(
            PILL_SMALL,
            PILL_HEIGHT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
                            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                            x = 0
                            y = 20
        }

        windowManager.addView(pillView, params)
        isOverlayAttached = true
    }

    private fun setupCompactContent() {
        pillView.removeAllViews()

        val centerSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        }

        if (isPulseActive) {
            pillView.addView(albumArt)
            pillView.addView(centerSpacer)
        } else if (isCallActive) {
            pillView.addView(albumArt)
            pillView.addView(centerSpacer)
        } else if (isTimerActive || isNavigationActive || isTransferActive) {
            timerText.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            pillView.addView(timerIcon)
            pillView.addView(centerSpacer)
            pillView.addView(timerText)
        } else {
            pillView.addView(albumArt)
            pillView.addView(centerSpacer)
            pillView.addView(if (visualizerMode == "WAVE") waveContainer else barsContainer)
        }
    }

    private fun startBarsAnimation() {
        stopBarsAnimation()
        for (i in 0 until barsContainer.childCount) {
            val bar = barsContainer.getChildAt(i)
            val animator = ValueAnimator.ofInt(8, 42).apply {
                duration = (280 + i * 90).toLong()
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                startDelay = (i * 100).toLong()
                addUpdateListener {
                    val p = bar.layoutParams
                    p.height = it.animatedValue as Int
                    bar.layoutParams = p
                }
            }
            animator.start()
            barAnimators.add(animator)
        }
    }

    private fun stopBarsAnimation() {
        barAnimators.forEach { it.cancel() }
        barAnimators.clear()
    }

    private fun startWaveAnimation() {
        stopWaveAnimation()
        for (i in 0 until waveContainer.childCount) {
            val wave = waveContainer.getChildAt(i)
            val animator = ValueAnimator.ofFloat(0.5f, 1.3f).apply {
                duration = (520 + i * 120).toLong()
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                startDelay = (i * 140).toLong()
                addUpdateListener {
                    val scaleY = it.animatedValue as Float
                    wave.scaleY = scaleY
                    wave.alpha = 0.55f + (scaleY - 0.5f) * 0.55f
                }
            }
            animator.start()
            waveAnimators.add(animator)
        }
    }

    private fun stopWaveAnimation() {
        waveAnimators.forEach { it.cancel() }
        waveAnimators.clear()
        if (::waveContainer.isInitialized) {
            for (i in 0 until waveContainer.childCount) {
                waveContainer.getChildAt(i).apply {
                    scaleY = 1f
                    alpha = 1f
                }
            }
        }
    }

    private fun stopMediaVisualizerAnimation() {
        stopBarsAnimation()
        stopWaveAnimation()
    }

    private fun showMediaVisualizer() {
        albumArt.visibility = View.VISIBLE
        if (visualizerMode == "WAVE") {
            barsContainer.visibility = View.GONE
            waveContainer.visibility = View.VISIBLE
            startWaveAnimation()
        } else {
            waveContainer.visibility = View.GONE
            barsContainer.visibility = View.VISIBLE
            startBarsAnimation()
        }
    }

    private fun applyVisualizerMode(mode: String) {
        visualizerMode = if (mode == "WAVE") "WAVE" else "BAR"
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(keyVisualizerMode, visualizerMode)
            .apply()

        if (!::pillView.isInitialized) return
        setupCompactContent()
        if (isMediaActive && !isTimerActive && !isNavigationActive && !isTransferActive && !isCallActive && !isPulseActive) {
            showMediaVisualizer()
        } else {
            stopMediaVisualizerAnimation()
            barsContainer.visibility = View.GONE
            waveContainer.visibility = View.GONE
        }
    }

    private fun startTimerIconAnimation() {
        if (timerIconAnimator?.isRunning == true) return
        timerIconAnimator?.cancel()
        timerIconAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                timerIcon.rotation = it.animatedValue as Float
            }
        }
        timerIconAnimator?.start()
    }

    private fun startNavigationIconAnimation() {
        if (navigationIconAnimator?.isRunning == true) return
        navigationIconAnimator?.cancel()
        navigationIconAnimator = ValueAnimator.ofFloat(0.88f, 1.08f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val scale = it.animatedValue as Float
                timerIcon.scaleX = scale
                timerIcon.scaleY = scale
            }
        }
        navigationIconAnimator?.start()
    }

    private fun stopNavigationIconAnimation() {
        navigationIconAnimator?.cancel()
        navigationIconAnimator = null
        if (::timerIcon.isInitialized) {
            timerIcon.scaleX = 1f
            timerIcon.scaleY = 1f
        }
    }

    private fun stopTimerIconAnimation() {
        timerIconAnimator?.cancel()
        timerIconAnimator = null
        if (::timerIcon.isInitialized) {
            timerIcon.rotation = 0f
        }
    }

    private fun renderStateAfterTransient() {
        if (!::pillView.isInitialized || !isOverlayAttached) return

        stopMediaVisualizerAnimation()
        if (!isTimerActive && !isTransferActive) stopTimerIconAnimation()
        if (!isNavigationActive) stopNavigationIconAnimation()

        when {
            isCallActive -> {
                setupCompactContent()
                animateWidth(PILL_CALL)
            }
            isTimerActive -> {
                timerIcon.setImageResource(R.drawable.ic_dynamic_timer)
                timerIcon.visibility = View.VISIBLE
                timerText.visibility = View.VISIBLE
                setupCompactContent()
                startTimerIconAnimation()
                animateWidth(PILL_LARGE)
            }
            isNavigationActive -> {
                timerIcon.setImageResource(R.drawable.ic_dynamic_media)
                timerIcon.visibility = View.VISIBLE
                timerText.visibility = View.VISIBLE
                setupCompactContent()
                startNavigationIconAnimation()
                animateWidth(PILL_LARGE)
            }
            isTransferActive -> {
                timerIcon.visibility = View.VISIBLE
                timerText.visibility = View.VISIBLE
                setupCompactContent()
                startTimerIconAnimation()
                animateWidth(PILL_LARGE)
            }
            isMediaActive -> {
                showMediaVisualizer()
                timerIcon.visibility = View.GONE
                timerText.visibility = View.GONE
                setupCompactContent()
                animateWidth(PILL_LARGE)
            }
            else -> {
                albumArt.visibility = View.GONE
                barsContainer.visibility = View.GONE
                timerIcon.visibility = View.GONE
                timerText.visibility = View.GONE
                setupCompactContent()
                animateWidth(PILL_SMALL)
            }
        }
    }

    private fun currentOperationMode(): String {
        return when {
            isCallActive -> "CALL"
            isPulseActive -> "NOTIFICATION"
            isTimerActive -> "TIMER"
            isNavigationActive -> "NAVIGATION"
            isTransferActive -> "TRANSFER"
            isMediaActive -> "MEDIA"
            else -> "NONE"
        }
    }

    private fun currentOperationPackage(): String? {
        return when {
            isCallActive -> activeCallSourcePackage
            isPulseActive -> activePulseSourcePackage
            isTimerActive -> activeTimerSourcePackage
            isNavigationActive -> activeNavigationSourcePackage
            isTransferActive -> activeTransferSourcePackage
            isMediaActive -> activeMediaSourcePackage
            else -> null
        }
    }

    private fun performTickVibration(durationMs: Long) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    }

    private fun clearPulseState() {
        visibilityHandler.removeCallbacks(pulseResetRunnable)
        pulseInAnimator?.cancel()
        pulseOutAnimator?.cancel()
        resetPulseAnimationState()
        isPulseActive = false
        activePulseSourcePackage = null
    }

    private fun playPulseInAnimation(generation: Int) {
        if (!::pillView.isInitialized) return
        pulseOutAnimator?.cancel()
        pulseInAnimator?.cancel()
        pillView.alpha = 0.72f
        pillView.scaleX = 0.93f
        pillView.scaleY = 0.93f
        pulseInAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_IN_DURATION_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                if (generation != pulseAnimationGeneration || !isPulseActive) return@addUpdateListener
                val t = it.animatedValue as Float
                pillView.alpha = 0.72f + (0.28f * t)
                pillView.scaleX = 0.93f + (0.07f * t)
                pillView.scaleY = 0.93f + (0.07f * t)
            }
        }
        pulseInAnimator?.start()
    }

    private fun playPulseOutAnimation(generation: Int, onEnd: () -> Unit) {
        if (!::pillView.isInitialized) {
            onEnd()
            return
        }
        pulseInAnimator?.cancel()
        pulseOutAnimator?.cancel()
        pulseOutAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_OUT_DURATION_MS
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (generation != pulseAnimationGeneration || !isPulseActive) return@addUpdateListener
                val t = it.animatedValue as Float
                pillView.alpha = 1f - (0.28f * t)
                pillView.scaleX = 1f - (0.07f * t)
                pillView.scaleY = 1f - (0.07f * t)
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) = Unit
                override fun onAnimationCancel(animation: android.animation.Animator) = Unit
                override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (generation != pulseAnimationGeneration) return
                    resetPulseAnimationState()
                    onEnd()
                }
            })
        }
        pulseOutAnimator?.start()
    }

    private fun resetPulseAnimationState() {
        if (!::pillView.isInitialized) return
        pillView.alpha = 1f
        pillView.scaleX = 1f
        pillView.scaleY = 1f
    }

    private fun formatTimerText(intent: Intent, isCountdown: Boolean): String {
        val millis = when {
            intent.hasExtra("remainingMs") -> intent.getLongExtra("remainingMs", 0L)
            intent.hasExtra("elapsedMs") -> intent.getLongExtra("elapsedMs", 0L)
            intent.hasExtra("milliseconds") -> intent.getLongExtra("milliseconds", 0L)
            intent.hasExtra("seconds") -> intent.getLongExtra("seconds", 0L) * 1000L
            else -> 0L
        }.coerceAtLeast(0L)

        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val timeText = if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }

        return if (isCountdown) "Kalan $timeText" else timeText
    }

    private fun animateWidth(to: Int) {
        if (!isOverlayAttached || !::pillView.isInitialized) return
        val params = pillView.layoutParams as WindowManager.LayoutParams
        val from = params.width
        if (from == to) return

        widthAnimator?.cancel()
        widthAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = 350
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                params.width = it.animatedValue as Int
                runCatching {
                    if (isOverlayAttached) {
                        windowManager.updateViewLayout(pillView, params)
                    }
                }
            }
        }
        widthAnimator?.start()
    }

    private fun startVisibilityMonitor() {
        visibilityHandler.removeCallbacks(visibilityCheckRunnable)
        visibilityHandler.post(visibilityCheckRunnable)
    }

    private fun stopVisibilityMonitor() {
        visibilityHandler.removeCallbacks(visibilityCheckRunnable)
    }

    private fun updatePillVisibilityForForegroundApp() {
        if (!::pillView.isInitialized || !isOverlayAttached) return

        val shouldHide = shouldHideForForegroundApp()
        if (shouldHide == isPillHiddenForForegroundApp) {
            if (!shouldHide && pillView.visibility != View.VISIBLE) {
                pillView.visibility = View.VISIBLE
            }
            return
        }

        isPillHiddenForForegroundApp = shouldHide
        if (shouldHide) {
            widthAnimator?.cancel()
            pillView.visibility = View.GONE
        } else {
            pillView.visibility = View.VISIBLE
            val targetWidth = if (isExpanded) {
                PILL_EXPANDED
            } else if (isCallActive) {
                PILL_CALL
            } else if (isPulseActive || isTimerActive || isNavigationActive || isTransferActive || isMediaActive) {
                PILL_LARGE
            } else {
                PILL_SMALL
            }
            if (isCallActive || isPulseActive || isTimerActive || isNavigationActive || isTransferActive) setupCompactContent()
            animateWidth(targetWidth)
        }
    }

    private fun shouldHideForForegroundApp(): Boolean {
        if (isPulseActive) return false

        if (isCallActive) {
            val callPackages = buildList {
                activeCallSourcePackage?.let { add(it) }
                addAll(CALL_PACKAGES)
            }
            return callPackages.any { isPackageForeground(it) }
        }

        if (isNavigationActive) {
            val navPackages = buildList {
                activeNavigationSourcePackage?.let { add(it) }
                add("com.google.android.apps.maps")
            }
            return navPackages.any { isPackageForeground(it) }
        }

        if (isTimerActive) {
            val timerPackages = buildList {
                activeTimerSourcePackage?.let { add(it) }
                addAll(CLOCK_PACKAGES)
            }
            return timerPackages.any { isPackageForeground(it) }
        }

        if (isTransferActive) {
            val transferPackages = buildList {
                activeTransferSourcePackage?.let { add(it) }
                add("com.instagram.android")
                add("com.whatsapp")
                add("com.google.android.apps.docs")
            }
            return transferPackages.any { isPackageForeground(it) }
        }

        if (isMediaActive && (activeMediaSourcePackage == SPOTIFY_PACKAGE || isPackageForeground(SPOTIFY_PACKAGE))) {
            return isPackageForeground(SPOTIFY_PACKAGE)
        }

        return false
    }

    private fun isPackageForeground(packageName: String): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = activityManager.runningAppProcesses ?: return false
        return runningProcesses.any {
            (it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) &&
                    (it.processName == packageName || it.processName.startsWith("$packageName:"))
        }
    }

    private fun resolveTimerSourcePackage(intent: Intent): String? {
        val explicit = intent.getStringExtra("sourcePackage")
            ?: intent.getStringExtra("ownerPackage")
            ?: intent.getStringExtra("packageName")
        if (!explicit.isNullOrBlank()) return explicit
        return CLOCK_PACKAGES.firstOrNull { isPackageInstalled(it) }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun setAlbumArtFromPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return runCatching {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable)
            setAlbumArtBitmapWithAdaptiveBackground(bitmap)
            true
        }.getOrDefault(false)
    }

    private fun setAlbumArtBitmapWithAdaptiveBackground(bitmap: Bitmap) {
        albumArt.setImageBitmap(bitmap)
        setAlbumArtBackground(pickAdaptiveBackgroundColor(bitmap))
    }

    private fun setAlbumArtBackground(color: Int) {
        (albumArt.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
    }

    private fun pickAdaptiveBackgroundColor(bitmap: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(bitmap, 18, 18, true)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0L
        for (x in 0 until sample.width) {
            for (y in 0 until sample.height) {
                val c = sample.getPixel(x, y)
                val alpha = Color.alpha(c)
                if (alpha < 24) continue
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                count++
            }
        }
        if (count == 0L) return DEFAULT_ICON_BG_COLOR

        val avgR = (r / count).toInt()
        val avgG = (g / count).toInt()
        val avgB = (b / count).toInt()

        // Make it more vivid while keeping a dark AMOLED-friendly tone.
        val hsv = FloatArray(3)
        Color.RGBToHSV(avgR, avgG, avgB, hsv)
        hsv[1] = (hsv[1] * 1.35f + 0.10f).coerceIn(0.38f, 0.92f)
        hsv[2] = (hsv[2] * 0.58f + 0.06f).coerceIn(0.20f, 0.44f)
        return Color.HSVToColor(hsv)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun expandControls() {
        isExpanded = true
        pillView.removeAllViews()

        val prevBtn = TextView(this).apply {
            text = "⏮"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(16, 0, 16, 0)
            setOnClickListener {
                sendBroadcast(Intent("MEDIA_COMMAND").putExtra("command", "PREV"))
            }
        }

        val playPauseBtn = TextView(this).apply {
            text = "⏸"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(16, 0, 16, 0)
            setOnClickListener {
                sendBroadcast(Intent("MEDIA_COMMAND").putExtra("command", "PLAY_PAUSE"))
            }
        }

        val nextBtn = TextView(this).apply {
            text = "⏭"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(16, 0, 16, 0)
            setOnClickListener {
                sendBroadcast(Intent("MEDIA_COMMAND").putExtra("command", "NEXT"))
            }
        }

        val songText = TextView(this).apply {
            text = "$currentArtist - $currentTitle"
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        pillView.addView(prevBtn)
        pillView.addView(playPauseBtn)
        pillView.addView(nextBtn)
        pillView.addView(songText)

        animateWidth(PILL_EXPANDED)
    }

    private fun collapseControls() {
        isExpanded = false
        setupCompactContent()
        animateWidth(
            if (isCallActive) PILL_CALL
            else if (isTimerActive || isNavigationActive || isTransferActive || isMediaActive) PILL_LARGE
            else PILL_SMALL
        )
    }

    private fun createNotification(): Notification {
        val channelId = "overlay_channel"
        val channel = NotificationChannel(
            channelId, "Dynamic Island", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Dynamic Island")
            .setContentText("Çalışıyor")
            .setSmallIcon(R.drawable.ic_stat_dynamic)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopVisibilityMonitor()
        visibilityHandler.removeCallbacks(pulseResetRunnable)
        pulseInAnimator?.cancel()
        pulseOutAnimator?.cancel()
        resetPulseAnimationState()
        widthAnimator?.cancel()
        stopTimerIconAnimation()
        stopNavigationIconAnimation()
        stopMediaVisualizerAnimation()
        if (isReceiverRegistered) {
            runCatching { unregisterReceiver(mediaReceiver) }
            isReceiverRegistered = false
        }
        if (::pillView.isInitialized && isOverlayAttached) {
            runCatching { windowManager.removeView(pillView) }
            isOverlayAttached = false
        }
    }


    override fun onBind(intent: Intent?): IBinder? = null
}

class ScreenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_SCREEN_ON) {
            val safeContext = context ?: return
            if (!Settings.canDrawOverlays(safeContext)) return
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    safeContext.startForegroundService(Intent(safeContext, OverlayService::class.java))
                } else {
                    safeContext.startService(Intent(safeContext, OverlayService::class.java))
                }
            }
        }
    }
}