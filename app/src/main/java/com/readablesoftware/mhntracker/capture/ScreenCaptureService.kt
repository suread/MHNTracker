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
import com.readablesoftware.mhntracker.detection.HuntReportDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        private const val CAPTURE_INTERVAL_MS = 500L  // 2fps
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureJob: Job? = null
    private var currentSessionDir: File? = null
    private var isCapturing = false
    private var frameIndex = 0

    private val detector = HuntReportDetector()
    private val serviceScope = CoroutineScope(Dispatchers.IO)

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

/*
    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        val width  = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi    = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MHNCapture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )
    }
*/

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
/*
    private fun startFrameLoop() {
        captureJob = serviceScope.launch {  // lambda: coroutine body
            while (true) {
                delay(CAPTURE_INTERVAL_MS)
                val bitmap = captureFrame() ?: continue

                when {
                    !isCapturing && detector.isHuntReportScreen(bitmap) -> {
                        isCapturing = true
                        frameIndex = 0
                        currentSessionDir = createSessionDir()
                        updateNotification("Capturing hunt report")
                        saveFrame(bitmap)
                    }
                    isCapturing && detector.isConfirmButtonVisible(bitmap) -> {
                        saveFrame(bitmap)
                        isCapturing = false
                        currentSessionDir = null
                        updateNotification("Waiting for hunt report")
                    }
                    isCapturing -> {
                        saveFrame(bitmap)
                    }
                }
            }
        }
    }
*/

    private fun startFrameLoop() {
        captureJob = serviceScope.launch {  // lambda: coroutine body
            while (true) {
                delay(CAPTURE_INTERVAL_MS)
                val bitmap = captureFrame() ?: continue

                when {
                    !isCapturing && detector.isHuntReportScreen(bitmap) -> {
                        Log.d("MHNCapture", "Hunt report detected — starting capture")
                        isCapturing = true
                        frameIndex = 0
                        currentSessionDir = createSessionDir()
                        updateNotification("Capturing hunt report")
                        saveFrame(bitmap)
                    }
                    isCapturing && detector.isConfirmButtonVisible(bitmap) -> {
                        Log.d("MHNCapture", "Confirm button detected — stopping capture, saved $frameIndex frames")
                        saveFrame(bitmap)
                        isCapturing = false
                        currentSessionDir = null
                        updateNotification("Waiting for hunt report")
                    }
                    isCapturing -> {
                        Log.d("MHNCapture", "Capturing frame $frameIndex")
                        saveFrame(bitmap)
                    }
                }
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

    private fun createSessionDir(): File {
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
        captureJob?.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        super.onDestroy()
    }
}