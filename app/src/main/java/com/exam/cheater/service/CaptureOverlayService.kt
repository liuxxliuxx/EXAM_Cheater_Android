package com.exam.cheater.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.exam.cheater.R
import com.exam.cheater.data.SettingsStore
import com.exam.cheater.model.AppSettings
import com.exam.cheater.network.VisionApiClient
import com.exam.cheater.util.ColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class CaptureOverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var settings: AppSettings = AppSettings()

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var captureJob: Job? = null

    private var windowManager: WindowManager? = null
    private var overlayContainer: FrameLayout? = null
    private var overlayTextView: TextView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    private var latestAnswer: String = "等待截图与识别..."

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        settings = SettingsStore.load(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_REFRESH_SETTINGS -> {
                settings = SettingsStore.load(this)
                ensureOverlay()
                applyOverlaySettings()
                return START_STICKY
            }

            ACTION_START -> {
                startAsForeground()
                settings = SettingsStore.load(this)
                ensureOverlay()
                applyOverlaySettings()

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val data = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != Int.MIN_VALUE && data != null) {
                    initProjection(resultCode, data)
                }
                startCaptureLoop()
                return START_STICKY
            }

            else -> {
                startAsForeground()
                settings = SettingsStore.load(this)
                ensureOverlay()
                applyOverlaySettings()
                startCaptureLoop()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        captureJob?.cancel()
        releaseProjection()
        removeOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            while (isActive) {
                settings = SettingsStore.load(this@CaptureOverlayService)
                ensureOverlay()
                applyOverlaySettings()

                if (mediaProjection == null || imageReader == null) {
                    updateOverlayText("请在控制台点击“授权录屏并启动”")
                    delay(1000)
                    continue
                }

                val screenshot = captureBitmapWithRetry()
                if (screenshot != null) {
                    val answer = withContext(Dispatchers.IO) {
                        VisionApiClient.requestAnswers(screenshot, settings)
                    }
                    screenshot.recycle()
                    latestAnswer = answer
                    updateOverlayText(answer)
                } else {
                    updateOverlayText("截图失败，等待下一次重试...")
                }

                delay(settings.intervalSeconds.coerceIn(1, 120) * 1000L)
            }
        }
    }

    private suspend fun captureBitmapWithRetry(maxWaitMs: Long = 1500): Bitmap? {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                return imageToBitmap(image)
            }
            delay(80)
        }
        return null
    }

    private fun imageToBitmap(image: Image): Bitmap {
        try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()
            return cropped
        } finally {
            image.close()
        }
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        releaseProjection()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (_: SecurityException) {
            null
        }

        if (mediaProjection == null) {
            updateOverlayText("录屏授权失效，请重新授权")
            return
        }

        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        virtualDisplay?.release()
        imageReader?.close()

        val (width, height) = getScreenSize()
        val densityDpi = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "exam_capture_virtual_display",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        if (virtualDisplay == null) {
            updateOverlayText("创建截图通道失败")
        }
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val wm = windowManager ?: return 1080 to 1920
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            val insets = wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            val width = (bounds.width() - insets.left - insets.right).coerceAtLeast(1)
            val height = (bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
            width to height
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val dm = resources.displayMetrics
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun ensureOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlayContainer != null && overlayTextView != null && overlayLayoutParams != null) return

        val textView = TextView(this).apply {
            text = latestAnswer
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isSingleLine = false
        }

        val container = FrameLayout(this).apply {
            background = ColorDrawable(android.graphics.Color.TRANSPARENT)
            addView(
                textView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val params = WindowManager.LayoutParams(
            settings.overlayWidth,
            settings.overlayHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.overlayX
            y = settings.overlayY
        }

        try {
            windowManager?.addView(container, params)
            overlayContainer = container
            overlayTextView = textView
            overlayLayoutParams = params
        } catch (_: Exception) {
            overlayContainer = null
            overlayTextView = null
            overlayLayoutParams = null
        }
    }

    private fun applyOverlaySettings() {
        val container = overlayContainer ?: return
        val textView = overlayTextView ?: return
        val params = overlayLayoutParams ?: return

        params.x = settings.overlayX
        params.y = settings.overlayY
        params.width = settings.overlayWidth.coerceAtLeast(100)
        params.height = settings.overlayHeight.coerceAtLeast(80)

        textView.textSize = settings.fontSizeSp
        val parsedTextColor = ColorUtils.parseHexColor(settings.textColorHex)
        textView.setTextColor(ColorUtils.withAlpha(parsedTextColor, settings.textAlpha))

        val borderDrawable = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            if (settings.borderEnabled) {
                val borderColor = ColorUtils.withAlpha(
                    ColorUtils.parseHexColor(settings.borderColorHex),
                    settings.borderAlpha
                )
                setStroke((settings.borderWidthDp * resources.displayMetrics.density).roundToInt(), borderColor)
            } else {
                setStroke(0, android.graphics.Color.TRANSPARENT)
            }
        }
        container.background = borderDrawable

        try {
            windowManager?.updateViewLayout(container, params)
        } catch (_: Exception) {
            removeOverlay()
            ensureOverlay()
            updateOverlayText(latestAnswer)
        }
    }

    private fun updateOverlayText(text: String) {
        overlayTextView?.text = text
    }

    private fun removeOverlay() {
        val view = overlayContainer ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        overlayContainer = null
        overlayTextView = null
        overlayLayoutParams = null
    }

    private fun releaseProjection() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    private fun stopEverything() {
        captureJob?.cancel()
        releaseProjection()
        removeOverlay()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在后台截图并识别题目")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Exam Capture Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T> Intent.getParcelableExtraCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            getParcelableExtra(key)
        }
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false

        const val ACTION_START = "com.exam.cheater.action.START"
        const val ACTION_STOP = "com.exam.cheater.action.STOP"
        const val ACTION_REFRESH_SETTINGS = "com.exam.cheater.action.REFRESH_SETTINGS"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "exam_capture_channel"
        private const val NOTIFICATION_ID = 2001
    }
}
