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
import android.util.Log
import android.view.Gravity
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
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var captureJob: Job? = null

    private var windowManager: WindowManager? = null
    private var overlayContainer: FrameLayout? = null
    private var overlayTextView: TextView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    private var latestAnswer: String = "Waiting for capture and recognition..."
    private var projectionStatusHint: String? = null

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
                settings = SettingsStore.load(this)
                ensureOverlay()
                applyOverlaySettings()
                projectionStatusHint = null

                if (!startAsForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val resultCodeFromIntent = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val dataFromIntent = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
                val resultCode = if (resultCodeFromIntent != Int.MIN_VALUE) {
                    resultCodeFromIntent
                } else {
                    cachedResultCode
                }
                val data = dataFromIntent ?: cachedResultData
                cachedResultCode = Int.MIN_VALUE
                cachedResultData = null

                if (resultCode != Int.MIN_VALUE && data != null) {
                    val projectionReady = initProjection(resultCode, data)
                    if (!projectionReady) {
                        if (projectionStatusHint.isNullOrBlank()) {
                            projectionStatusHint = "Screen capture init failed. Please grant permission again."
                            updateOverlayText(projectionStatusHint!!)
                        }
                    }
                } else {
                    Log.e(TAG, "Missing projection permission data. resultCode=$resultCode dataNull=${data == null}")
                    projectionStatusHint = "Missing screen-capture permission data. Tap grant again."
                    updateOverlayText(projectionStatusHint!!)
                }
                startCaptureLoop()
                return START_STICKY
            }

            else -> {
                if (!startAsForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
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
                    updateOverlayText(projectionStatusHint ?: "Tap \"Grant Screen Capture & Start\" in the app first.")
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
                    projectionStatusHint = null
                    updateOverlayText(answer)
                } else {
                    updateOverlayText("Capture failed, waiting for next retry...")
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

    private fun initProjection(resultCode: Int, data: Intent): Boolean {
        releaseProjection()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            Log.e(TAG, "getMediaProjection failed", e)
            projectionStatusHint = "System rejected screen-capture token. Please grant again."
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected getMediaProjection error", e)
            projectionStatusHint = "Failed to obtain MediaProjection: ${e.javaClass.simpleName}"
            null
        }

        if (mediaProjection == null) {
            if (projectionStatusHint.isNullOrBlank()) {
                projectionStatusHint = "Screen-capture authorization invalid, please grant again."
            }
            updateOverlayText(projectionStatusHint!!)
            return false
        }

        registerProjectionCallback(mediaProjection!!)
        return setupVirtualDisplay()
    }

    private fun setupVirtualDisplay(): Boolean {
        virtualDisplay?.release()
        imageReader?.close()

        val (width, height) = getScreenSize()
        val densityDpi = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = try {
            mediaProjection?.createVirtualDisplay(
                "exam_capture_virtual_display",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
        } catch (e: IllegalStateException) {
            Log.e(TAG, "createVirtualDisplay illegal state", e)
            projectionStatusHint = "Projection state invalid. Please grant capture again."
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            projectionStatusHint = "System denied virtual display creation. Please grant again."
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected createVirtualDisplay error", e)
            projectionStatusHint = "Virtual display error: ${e.javaClass.simpleName}"
            null
        }

        if (virtualDisplay == null) {
            if (projectionStatusHint.isNullOrBlank()) {
                projectionStatusHint = "Failed to create capture channel."
            }
            updateOverlayText(projectionStatusHint!!)
            imageReader?.close()
            imageReader = null
            return false
        }
        projectionStatusHint = null
        return true
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val wm = windowManager ?: return 1080 to 1920
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            val width = bounds.width().coerceAtLeast(1)
            val height = bounds.height().coerceAtLeast(1)
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
        val projection = mediaProjection
        val callback = mediaProjectionCallback
        if (projection != null && callback != null) {
            try {
                projection.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        mediaProjectionCallback = null

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
            projection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    private fun registerProjectionCallback(projection: MediaProjection) {
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system/user")
                projectionStatusHint = "Screen capture stopped by system. Tap grant to resume."
                serviceScope.launch {
                    updateOverlayText(projectionStatusHint!!)
                }
                releaseProjection()
            }
        }
        try {
            projection.registerCallback(callback, null)
            mediaProjectionCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "registerCallback failed", e)
            projectionStatusHint = "Failed to register projection callback."
        }
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

    private fun startAsForeground(): Boolean {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Capture service is running")
            .setOngoing(true)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "startForeground(mediaProjection) failed", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
                true
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Fallback startForeground failed", fallbackError)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            false
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
        private const val TAG = "CaptureOverlaySvc"

        @Volatile
        private var cachedResultCode: Int = Int.MIN_VALUE

        @Volatile
        private var cachedResultData: Intent? = null

        fun cacheProjectionPermission(resultCode: Int, data: Intent) {
            cachedResultCode = resultCode
            cachedResultData = data
        }
    }
}
