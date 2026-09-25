package com.genned.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
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
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.genned.app.GennedApplication
import com.genned.app.MainActivity
import com.genned.app.R
import com.genned.app.ui.navigation.Routes
import com.genned.domain.model.AnalysisInput
import com.genned.domain.model.Classification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Experimental, opt-in-only foreground service backing the floating "Screen
 * Overlay" bubble (Settings -> Experimental). See internal-docs/ARCHITECTURE.md "Screen
 * overlay (experimental)" for the full design and why it captures the screen via
 * [MediaProjection] rather than reading other apps' content via Accessibility
 * Service.
 *
 * Lifecycle: started only from SettingsScreen, only after the user has explicitly
 * granted the overlay-draw permission, usage-access permission, and a
 * MediaProjection consent for this session. Stopped the moment the user turns the
 * experiment off (or taps Stop on the persistent notification this foreground
 * service is required to show), or if the MediaProjection is revoked by the
 * system. Nothing here runs at boot or starts itself.
 */
class OverlayCaptureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var analysisJob: Job? = null
    private var watchJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var bubbleView: BubbleView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lastAnalysisId: Long? = null

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var captureReader: ImageReader? = null
    private var captureDisplay: VirtualDisplay? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var screenTopInset = 0
    private var screenBottomInset = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "onStartCommand: ACTION_STOP received, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }
        if (data == null || resultCode == 0) {
            Log.w(TAG, "onStartCommand: missing MediaProjection result data, stopping without starting")
            stopSelf()
            return START_NOT_STICKY
        }

        if (mediaProjection != null) {
            Log.i(TAG, "onStartCommand: already running, tearing down the previous session before restarting")
            tearDownSession()
        }

        Log.i(TAG, "onStartCommand: starting overlay (foreground service + projection + bubble)")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        startProjection(resultCode, data)
        addBubble()
        watchForegroundApp()
        _running.value = true

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: stopping overlay (this is the moment battery cost should end)")
        tearDownSession()
        serviceScope.cancel()
        _running.value = false
        super.onDestroy()
    }

    private fun tearDownSession() {
        watchJob?.cancel()
        watchJob = null
        analysisJob?.cancel()
        analysisJob = null
        removeBubble()
        closeCaptureSurface()
        stopProjection()
    }

    // --- Foreground-app awareness: show the bubble specifically over the apps
    // this experiment targets, hide it elsewhere. See ForegroundAppWatcher. ---

    private fun watchForegroundApp() {
        watchJob = serviceScope.launch {
            ForegroundAppWatcher(this@OverlayCaptureService).watch().collect { packageName ->
                val shouldShow = packageName != null && packageName in ForegroundAppWatcher.TARGET_PACKAGES
                Log.d(TAG, "foregroundPackage=$packageName shouldShowBubble=$shouldShow")
                bubbleView?.visibility = if (shouldShow) android.view.View.VISIBLE else android.view.View.GONE

                // Created once, then paused/resumed with bubble visibility - see the
                // comment above startProjection for why it must never be re-created.
                if (shouldShow) {
                    openCaptureSurface()
                } else {
                    pauseCaptureSurface()
                }
            }
        }
    }

    // --- MediaProjection setup ---
    //
    // The MediaProjection consent (below) is obtained once and held for the whole
    // service lifetime, and so is the VirtualDisplay/ImageReader it feeds: Android
    // 14+ allows only ONE createVirtualDisplay() call per MediaProjection grant - a
    // second call revokes the grant (MediaProjection.Callback.onStop()), seen
    // on-device as the overlay dying on the 2nd tap (when displays were per-tap)
    // and then on re-entering Instagram (when they were per-visit). A Service can't
    // silently re-request consent (that needs an Activity to show the system
    // dialog), so the display is created once, on the first visit, and afterwards
    // only paused/resumed via VirtualDisplay.setSurface: detaching the surface is
    // like turning that virtual screen off, so nothing is mirrored (no GPU/battery
    // cost) while the bubble is hidden. It's released only when the overlay stops.

    private fun startProjection(resultCode: Int, data: Intent) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        // A Service isn't a UI context; if the platform refuses window metrics here, crop without insets.
        val (topInset, bottomInset) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val insets = windowManager.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                insets.top to insets.bottom
            }.getOrNull() ?: (0 to 0)
        } else {
            0 to 0
        }
        screenTopInset = topInset
        screenBottomInset = bottomInset

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection = projection

        // Required since Android 14 (API 34): createVirtualDisplay() throws
        // IllegalStateException if no callback is registered first. onStop fires
        // when the user revokes capture from the system's "stop sharing" control,
        // not just when we call stop() ourselves - without handling it here, every
        // capture attempt after a system-initiated revoke would crash instead of
        // failing gracefully.
        //
        // It also fires unprompted on some devices/OS versions well before any user
        // action - seen on-device as the whole overlay silently vanishing mid-tap
        // (openCaptureSurface immediately followed by onDestroy, no error shown -
        // see internal-docs/ARCHITECTURE.md "Screen overlay (experimental)"). Since we can't
        // distinguish "user tapped the system stop-sharing control" from "the OS
        // revoked this MediaProjection on its own" from inside this callback, at
        // least stop being silent about it: log it distinctly from a
        // user-requested ACTION_STOP, and tell the user directly, since otherwise
        // the bubble just disappears with no explanation and no persistent
        // notification to point back to.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection.onStop(): capture permission ended (system-revoked, not our own ACTION_STOP) - stopping overlay")
                Toast.makeText(
                    this@OverlayCaptureService,
                    getString(R.string.overlay_projection_stopped_toast),
                    Toast.LENGTH_LONG,
                ).show()
                stopSelf()
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun stopProjection() {
        projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        projectionCallback = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun openCaptureSurface() {
        val existing = captureDisplay
        val existingReader = captureReader
        if (existing != null && existingReader != null) {
            Log.d(TAG, "resumeCaptureSurface at ${System.currentTimeMillis()}")
            existing.setSurface(existingReader.surface)
            return
        }
        val projection = mediaProjection ?: return
        Log.d(TAG, "openCaptureSurface at ${System.currentTimeMillis()}")
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        val display = projection.createVirtualDisplay(
            "GennedOverlayCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
        captureReader = reader
        captureDisplay = display
    }

    private fun pauseCaptureSurface() {
        val display = captureDisplay ?: return
        Log.d(TAG, "pauseCaptureSurface at ${System.currentTimeMillis()}")
        display.setSurface(null)
    }

    private fun closeCaptureSurface() {
        val reader = captureReader ?: return
        val display = captureDisplay
        Log.d(TAG, "closeCaptureSurface at ${System.currentTimeMillis()}")
        display?.release()
        reader.close()
        captureReader = null
        captureDisplay = null
    }

    // --- Bubble window ---

    private fun addBubble() {
        val bubble = BubbleView(this)
        bubble.visibility = android.view.View.GONE // shown only over target apps, see watchForegroundApp()
        bubbleView = bubble

        val size = (56 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenHeight / 3
        }
        layoutParams = params

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) moved = true
                    params.x = startX + dx.roundToInt()
                    params.y = startY + dy.roundToInt()
                    windowManager.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleTapped()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, params)
    }

    private fun overlayWindowType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    // --- Capture + analyze ---

    private fun onBubbleTapped() {
        val bubble = bubbleView ?: return

        if (bubble.state == BubbleState.RESULT) {
            lastAnalysisId?.let { openResult(it) }
            return
        }
        if (analysisJob?.isActive == true) return

        val reader = captureReader ?: return

        analysisJob = serviceScope.launch {
            // Hide the bubble so it isn't part of the frame being classified.
            bubble.visibility = View.INVISIBLE
            val capturedFile = try {
                // Drop any frame queued while the bubble was still drawn.
                runCatching { reader.acquireLatestImage()?.close() }
                delay(CAPTURE_SETTLE_MS)
                runCatching { captureFrame(reader) }.getOrNull()
            } finally {
                // watchForegroundApp may have set GONE meanwhile; don't override that.
                if (bubble.visibility == View.INVISIBLE) bubble.visibility = View.VISIBLE
            }
            bubble.state = BubbleState.ANALYZING
            bubble.startAnalyzingAnimation()

            if (capturedFile == null) {
                showTransientError(bubble)
                return@launch
            }

            // finally, so the screen capture is removed even if the service stops mid-analysis.
            val outcome = try {
                val cropSize = captureCrop()[2]
                val container = (application as GennedApplication).container
                val input = AnalysisInput(
                    originalFilePath = capturedFile.absolutePath,
                    normalizedFilePath = capturedFile.absolutePath,
                    originalMimeType = "image/jpeg",
                    widthPx = cropSize,
                    heightPx = cropSize,
                    fileSizeBytes = capturedFile.length(),
                )
                runCatching { container.analyzeImageUseCase.run(input, capturedFile) { } }
            } finally {
                capturedFile.delete()
            }
            val (analysisId, result) = outcome.getOrNull() ?: run {
                showTransientError(bubble)
                return@launch
            }

            bubble.stopAnalyzingAnimation()
            lastAnalysisId = analysisId
            bubble.resultPercent = (result.aiLikelihood * 100).roundToInt()
            bubble.resultColor = colorFor(result.classification)
            bubble.state = BubbleState.RESULT

            delay(RESULT_DISPLAY_MS)
            if (bubble.state == BubbleState.RESULT) bubble.state = BubbleState.IDLE
        }
    }

    private suspend fun showTransientError(bubble: BubbleView) {
        bubble.stopAnalyzingAnimation()
        bubble.state = BubbleState.ERROR
        delay(RESULT_DISPLAY_MS)
        if (bubble.state == BubbleState.ERROR) bubble.state = BubbleState.IDLE
    }

    private suspend fun captureFrame(reader: ImageReader): File? = withContext(Dispatchers.IO) {
        // A just-opened VirtualDisplay (or one that hasn't produced a frame since
        // its last read) needs a frame or two to catch up; briefly poll rather than
        // failing on the first miss. The surface itself is NOT managed here - see
        // openCaptureSurface/pauseCaptureSurface, tied to bubble visibility.
        var image: Image? = null
        for (attempt in 0 until FRAME_POLL_ATTEMPTS) {
            image = reader.acquireLatestImage()
            if (image != null) break
            Thread.sleep(FRAME_POLL_DELAY_MS)
        }
        val img = image ?: return@withContext null

        try {
            val plane = img.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val rawBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888,
            )
            rawBitmap.copyPixelsFromBuffer(buffer)
            val (left, top, size) = captureCrop()
            val cropped = Bitmap.createBitmap(rawBitmap, left, top, size, size)
            if (cropped !== rawBitmap) rawBitmap.recycle()

            val outDir = File(cacheDir, "shared").apply { mkdirs() }
            val outFile = File(outDir, "overlay_capture_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { out -> cropped.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            cropped.recycle()
            outFile
        } finally {
            img.close()
        }
    }

    private fun captureCrop(): IntArray =
        CaptureCrop.contentSquare(screenWidth, screenHeight, screenTopInset, screenBottomInset)

    private fun colorFor(classification: Classification): Int = when (classification) {
        Classification.HIGH -> Color.parseColor("#D8342A")
        Classification.UNCERTAIN -> Color.parseColor("#B5860B")
        Classification.LOW -> Color.parseColor("#2E7D4F")
    }

    private fun openResult(analysisId: Long) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Routes.EXTRA_OPEN_ANALYSIS_ID, analysisId)
        }
        startActivity(intent)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.overlay_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, OverlayCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_body))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(0, getString(R.string.overlay_notification_stop), stopIntent)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_STOP = "com.genned.app.overlay.STOP"

        private const val CHANNEL_ID = "overlay_capture"
        private const val NOTIFICATION_ID = 4201
        private const val TOUCH_SLOP = 12
        private const val RESULT_DISPLAY_MS = 6_000L
        private const val FRAME_POLL_ATTEMPTS = 10
        private const val FRAME_POLL_DELAY_MS = 80L
        private const val CAPTURE_SETTLE_MS = 120L
        private const val TAG = "OverlayCaptureService"

        private val _running = MutableStateFlow(false)

        /** Whether the overlay session is live, so Settings can reflect it after being reopened. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, OverlayCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
    }
}
