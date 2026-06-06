package com.readablesoftware.mhntracker.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
import com.readablesoftware.mhntracker.detection.FightEventDetector
import com.readablesoftware.mhntracker.detection.HuntReportDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Log

class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "mhn_capture_channel"
        private const val CAPTURE_INTERVAL_MS = 200L   // 5fps
        private const val MAX_SESSION_FRAMES  = 300    // safety cap: 30 seconds at 10fps
        private const val FRAME_CHANNEL_CAPACITY = 2
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var producerJob: Job? = null
    private var consumerJob: Job? = null
    private var currentSessionDir: File? = null

    // Written only by consumer. Read by producer if variable rates are added later.
    @Volatile private var isCapturing = false

    private var frameIndex = 0

    private val detector by lazy { HuntReportDetector.create(this) }
    private val fightEventDetector = FightEventDetector()
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    // Channel connecting producer to consumer. Fixed capacity; drop-oldest when full.
    private val frameChannel = Channel<Bitmap>(capacity = FRAME_CHANNEL_CAPACITY)

    // Flat directory for break frames — created lazily on first save.
    // All breaks across all fights land here, distinguished by timestamp filename.
    private val breaksDir: File by lazy {
        File(getExternalFilesDir(null), "breaks")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for hunt report"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: return START_NOT_STICKY
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            ?: return START_NOT_STICKY

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        setupVirtualDisplay()
        startFrameLoop()

        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        val width  = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi    = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {  // lambda: anonymous class
            override fun onStop() {
                virtualDisplay?.release()
                imageReader?.close()
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
    }

    private fun startFrameLoop() {

        // ── Producer ─────────────────────────────────────────────────────────
        // Captures frames at a fixed rate. Never blocks on detection.
        // If the channel is full, drops the oldest frame and sends the new one.
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

        // ── Consumer ─────────────────────────────────────────────────────────
        // Runs detection on each frame. Owns all session state.
        // Break detection is suppressed during hunt report capture.
        consumerJob = serviceScope.launch {
            for (bitmap in frameChannel) {
                val t0 = System.currentTimeMillis()

                if (isCapturing) {
                    // ── isConfirmButtonVisible ────────────────────────────
                    val t1 = System.currentTimeMillis()
                    val confirmVisible = detector.isConfirmButtonVisible(bitmap)
                    Log.d("MHNTiming", "isConfirmButtonVisible: ${System.currentTimeMillis() - t1}ms  result=$confirmVisible")

                    when {
                        confirmVisible -> {
                            Log.d("MHNCapture", "Confirm button detected — stopping capture, saved $frameIndex frames")
                            saveFrame(bitmap)
                            isCapturing = false
                            currentSessionDir = null
                            updateNotification("Waiting for hunt report")
                        }
                        frameIndex >= MAX_SESSION_FRAMES -> {
                            Log.w("MHNCapture", "Frame cap reached ($MAX_SESSION_FRAMES) — stopping capture without confirm button")
                            isCapturing = false
                            currentSessionDir = null
                            updateNotification("Waiting for hunt report")
                        }
                        else -> {
                            Log.d("MHNCapture", "Capturing frame $frameIndex")
                            saveFrame(bitmap)
                        }
                    }

                } else {
                    // ── isHuntReportScreen ────────────────────────────────
                    val t1 = System.currentTimeMillis()
                    val huntVisible = detector.isHuntReportScreen(bitmap)
                    Log.d("MHNTiming", "isHuntReportScreen: ${System.currentTimeMillis() - t1}ms  result=$huntVisible")

                    if (huntVisible) {
                        Log.d("MHNCapture", "Hunt report detected — starting capture")
                        isCapturing = true
                        frameIndex = 0
                        currentSessionDir = createSessionDir()
                        updateNotification("Capturing hunt report")
                        saveFrame(bitmap)
                    }

                    // ── isBreakVisible ────────────────────────────────────
                    // Suppressed during hunt report capture — no breaks occur
                    // on the rewards screen.
                    val t2 = System.currentTimeMillis()
                    val breakVisible = fightEventDetector.isBreakVisible(bitmap)
                    Log.d("MHNTiming", "isBreakVisible: ${System.currentTimeMillis() - t2}ms  result=$breakVisible")

                    if (breakVisible) {
                        Log.d("MHNCapture", "BREAK detected — saving break frame")
                        saveBreakFrame(bitmap)
                    }
                }

                Log.d("MHNTiming", "consumer: total ${System.currentTimeMillis() - t0}ms  isCapturing=$isCapturing")
            }
        }
    }

    private fun captureFrame(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride   = planes[0].rowStride
            val rowPadding  = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } finally {
            image.close()  // must always be closed
        }
    }

    private fun saveFrame(bitmap: Bitmap) {
        val dir = currentSessionDir ?: return
        val file = File(dir, "frame_${frameIndex.toString().padStart(4, '0')}.png")
        FileOutputStream(file).use { stream ->  // lambda
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        frameIndex++
    }

    private fun saveBreakFrame(bitmap: Bitmap) {
        getExternalFilesDir(null)?.mkdirs()  // recreate if deleted mid-run
        breaksDir.mkdirs()  // no-op if exists, recreates if deleted between pulls
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.UK).format(Date())
        val file = File(breaksDir, "break_$timestamp.png")
        FileOutputStream(file).use { stream ->  // lambda
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        Log.d("MHNCapture", "Break frame saved: ${file.name}")
    }

    private fun createSessionDir(): File {
        getExternalFilesDir(null)?.mkdirs()  // recreate if deleted mid-run
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(Date())
        val dir = File(getExternalFilesDir(null), "sessions/$timestamp")
        dir.mkdirs()
        return dir
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MHN Capture",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        producerJob?.cancel()
        consumerJob?.cancel()
        frameChannel.close()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        super.onDestroy()
    }
}