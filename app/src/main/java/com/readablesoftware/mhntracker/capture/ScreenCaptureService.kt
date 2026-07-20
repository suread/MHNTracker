package com.readablesoftware.mhntracker.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.readablesoftware.mhntracker.detection.AppStateDetector
import com.readablesoftware.mhntracker.detection.FightEventDetector
import com.readablesoftware.mhntracker.detection.FightHandler
import com.readablesoftware.mhntracker.detection.FightStartDetector
import com.readablesoftware.mhntracker.detection.HandlerStatus
import com.readablesoftware.mhntracker.detection.HuntReportDetector
import com.readablesoftware.mhntracker.detection.SessionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log
import androidx.core.graphics.createBitmap

/**
 * Central router for MHN screen capture.
 *
 * Responsibilities:
 *   - Owns MediaProjection, VirtualDisplay, and ImageReader.
 *   - Runs a producer/consumer frame loop at 5fps.
 *   - Performs per-frame screen-wide checks (black screen, map) that apply
 *     regardless of which handler is active.
 *   - Polls registered handlers at the slow-check rate to find one that wants
 *     to activate, then routes frames to it until it finishes or is terminated.
 *
 * Handler lifecycle:
 *   - Handlers are checked in registration order (priority order).
 *   - At most one handler is active at a time.
 *   - When a handler returns [HandlerStatus.DONE], it is deactivated and the
 *     router returns to polling for the next trigger.
 *   - When a map signal fires, the active handler (if any) is terminated via
 *     [SessionHandler.onTerminate] and the router returns to idle polling.
 *   - If two handlers both recognise their trigger on the same frame, the
 *     first by registration order wins; an error is logged.
 *
 * Adding a new handler:
 *   Construct it in [buildHandlers] and add it to the returned list. Priority
 *   is determined by list order — time-critical / irreversible handlers first.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "mhn_capture_channel"

        // Producer supplies frames at this fixed rate.
        private const val CAPTURE_INTERVAL_MS = 200L   // 5fps

        private const val FRAME_CHANNEL_CAPACITY = 2

        // Slow checks (map detection, trigger polling) run every Nth frame
        // consumed by the consumer. 3 × 200ms = 600ms between slow checks.
        private const val SLOW_CHECK_EVERY_N_FRAMES = 3

        private const val TAG = "MHNRouter"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var producerJob: Job? = null
    private var consumerJob: Job? = null

    // All mutable state is owned exclusively by the consumer coroutine.
    private var activeHandler: SessionHandler? = null
    private var slowCheckCounter = 0

    // Handlers checked in priority order. Built once in onCreate.
    private lateinit var handlers: List<SessionHandler>

    private val appStateDetector = AppStateDetector()
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val frameChannel = Channel<Bitmap>(capacity = FRAME_CHANNEL_CAPACITY)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        handlers = buildHandlers()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Idle"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: return START_NOT_STICKY
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            ?: return START_NOT_STICKY

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        setupVirtualDisplay()
        startFrameLoop()

        return START_NOT_STICKY
    }

    // ── Handler registration ──────────────────────────────────────────────

    /**
     * Constructs and returns the ordered list of session handlers.
     * Priority is determined by list order — first entry is highest priority.
     *
     * To add a new handler: construct it here and insert it at the appropriate
     * priority position. Time-critical / irreversible activities (fight) come
     * before recoverable ones (inventory).
     */
    private fun buildHandlers(): List<SessionHandler> {
        val baseDir = getExternalFilesDir(null) ?: filesDir

        val fightHandler = FightHandler(
            fightStartDetector = FightStartDetector.create(this),
            huntReportDetector = HuntReportDetector.create(this),
            fightEventDetector = FightEventDetector(),
            baseDir            = baseDir,
        )

        // Registration order = priority order.
        // Add future handlers (InventoryHandler, etc.) below fightHandler.
        return listOf(fightHandler)
    }

    // ── Virtual display ───────────────────────────────────────────────────

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        val width  = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi    = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                virtualDisplay?.release()
                imageReader?.close()
                AppState.setMediaProjectionActive(CaptureStatus.INACTIVE)
                stopSelf()
            }
        }, null)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MHNCapture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )

        AppState.setMediaProjectionActive(CaptureStatus.ACTIVE)

    }

    // ── Frame loop ────────────────────────────────────────────────────────

    private fun startFrameLoop() {

        // ── Producer ─────────────────────────────────────────────────────
        // Captures at fixed 5fps. Knows nothing about app state or handlers.
        // Drops the oldest frame if the consumer is busy.
        producerJob = serviceScope.launch {
            while (true) {
                val frameStart = System.currentTimeMillis()

                val bitmap = captureFrame()
                if (bitmap != null) {
                    if (!frameChannel.trySend(bitmap).isSuccess) {
                        frameChannel.tryReceive()   // discard oldest
                        frameChannel.trySend(bitmap)
                        Log.d("MHNTiming", "producer: channel full, oldest frame dropped")
                    }
                }

                val elapsed = System.currentTimeMillis() - frameStart
                delay(maxOf(0L, CAPTURE_INTERVAL_MS - elapsed))
            }
        }

        // ── Consumer ─────────────────────────────────────────────────────
        // Owns all routing state. Processes or discards each frame.
        consumerJob = serviceScope.launch {
            for (bitmap in frameChannel) {
                val t0 = System.currentTimeMillis()
                routeFrame(bitmap)
                Log.d("MHNTiming", "consumer total: ${System.currentTimeMillis() - t0}ms  " +
                        "activeHandler=${activeHandler?.javaClass?.simpleName ?: "none"}")
            }
        }
    }

    // ── Per-frame routing ─────────────────────────────────────────────────

    /**
     * Main per-frame dispatch. Called by the consumer for every received frame.
     *
     * Order:
     *   1. Black screen pre-filter — cheapest check, gates everything else.
     *   2. Slow checks (every [SLOW_CHECK_EVERY_N_FRAMES]):
     *        a. Map detection — terminates the active handler if map is visible.
     *        b. Trigger polling — if no handler is active, ask each handler
     *           in priority order whether it recognises its trigger.
     *   3. Active handler dispatch — if a handler is active, send it the frame.
     */
    private fun routeFrame(bitmap: Bitmap) {

        // ── 1. Black screen pre-filter ────────────────────────────────────
        val t1 = System.currentTimeMillis()
        if (appStateDetector.isBlackScreen(bitmap)) {
            Log.d("MHNTiming", "isBlackScreen: ${System.currentTimeMillis() - t1}ms  result=true")
            Log.d(TAG, "Black screen — skipping all detection")
            // TODO: signal overlay bubble to hide when overlay is implemented
            return
        }
        Log.d("MHNTiming", "isBlackScreen: ${System.currentTimeMillis() - t1}ms  result=false")

        // ── 2. Slow checks ────────────────────────────────────────────────
        slowCheckCounter++
        if (slowCheckCounter % SLOW_CHECK_EVERY_N_FRAMES == 0) {

            // 2a. Map detection — valid in any state
            val t2 = System.currentTimeMillis()
            val mapVisible = appStateDetector.isMapScreen(bitmap)
            Log.d("MHNTiming", "isMapScreen: ${System.currentTimeMillis() - t2}ms  result=$mapVisible")

            if (mapVisible) {
                Log.d(TAG, "Map detected — terminating active handler " +
                        "(${activeHandler?.javaClass?.simpleName ?: "none"})")
                activeHandler?.onTerminate()
                activeHandler = null
                updateNotification("Idle")
                return
            }

            // 2b. Trigger polling — only when no handler is active
            if (activeHandler == null) {
                pollTriggers(bitmap)
                // If a handler was just activated by pollTriggers, it has
                // already consumed this frame via recognisesTrigger — return
                // without calling onFrame.
                if (activeHandler != null) return
            }
        }

        // ── 3. Active handler dispatch ────────────────────────────────────
        val handler = activeHandler ?: return   // idle, nothing to dispatch

        val t3 = System.currentTimeMillis()
        val status = handler.onFrame(bitmap)
        Log.d("MHNTiming", "handler.onFrame: ${System.currentTimeMillis() - t3}ms  status=$status")

        if (status == HandlerStatus.DONE) {
            Log.d(TAG, "Handler ${handler.javaClass.simpleName} finished")
            activeHandler = null
            AppState.setMediaProjectionActive(CaptureStatus.ACTIVE) // MediaProjection is active, fight not being recorded
            updateNotification("Idle")
        }
    }

    /**
     * Polls each registered handler in priority order to find one that
     * recognises its trigger on this frame.
     *
     * Activates the first handler that returns true from [SessionHandler
     * .recognisesTrigger]. If more than one handler fires, logs an error —
     * this indicates overlapping trigger conditions that should be investigated.
     *
     * The frame is considered consumed by [SessionHandler.recognisesTrigger]
     * — the caller must not forward it to [SessionHandler.onFrame].
     */
    private fun pollTriggers(bitmap: Bitmap) {
        var activated: SessionHandler? = null

        for (handler in handlers) {
            val t = System.currentTimeMillis()
            val triggered = handler.recognisesTrigger(bitmap)
            Log.d("MHNTiming", "${handler.javaClass.simpleName}.recognisesTrigger: " +
                    "${System.currentTimeMillis() - t}ms  result=$triggered")

            if (triggered) {
                if (activated == null) {
                    activated = handler
                    activeHandler = handler
                    val name = handler.javaClass.simpleName
                    AppState.setMediaProjectionActive(CaptureStatus.IN_FIGHT)
                    Log.d(TAG, "Handler activated: $name")
                    updateNotification(notificationTextFor(handler))
                } else {
                    // Two triggers fired on the same frame — log error but
                    // continue: first-by-priority handler stays active.
                    Log.e(TAG, "Multiple handlers triggered on the same frame! " +
                            "Active: ${activated.javaClass.simpleName}, " +
                            "ignored: ${handler.javaClass.simpleName}. " +
                            "Review trigger conditions for overlap.")
                }
            }
        }
    }

    /**
     * Returns a human-readable notification status string for the given handler.
     * Extend this when new handlers are added.
     */
    private fun notificationTextFor(handler: SessionHandler): String {
        return when (handler) {
            is FightHandler -> "Fight in progress"
            else            -> "Capture active"
        }
    }

    // ── Frame capture ─────────────────────────────────────────────────────

    private fun captureFrame(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val planes      = image.planes
            val buffer      = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride   = planes[0].rowStride
            val rowPadding  = rowStride - pixelStride * image.width

            val bitmap = createBitmap(image.width + rowPadding / pixelStride, image.height)
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } finally {
            image.close()
        }
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "MHN Capture", NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MHN Tracker")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onDestroy() {
        producerJob?.cancel()
        consumerJob?.cancel()
        frameChannel.close()
        activeHandler?.onTerminate()
        activeHandler = null
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        AppState.setMediaProjectionActive(CaptureStatus.INACTIVE)
        super.onDestroy()
    }
}