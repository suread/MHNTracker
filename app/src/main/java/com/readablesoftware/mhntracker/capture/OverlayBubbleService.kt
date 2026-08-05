package com.readablesoftware.mhntracker.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayBubbleService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "mhn_bubble_channel"
        private const val PREFS_NAME = "bubble_prefs"
        private const val PREF_X = "bubble_x"
        private const val PREF_Y = "bubble_y"

    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: ImageView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var observerJob: Job? = null

    // Drag tracking
    private var dragInitialX = 0
    private var dragInitialY = 0
    private var dragTouchX   = 0f
    private var dragTouchY   = 0f
    private var isDragging   = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs         = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        createBubble()
        observeState()
    }

    private fun createBubble() {
        bubbleView = ImageView(this)
        updateBubbleAppearance(status = CaptureStatus.INACTIVE)

        val sizePx = dpToPx(controller.appearanceForStatus(CaptureStatus.INACTIVE).sizeDp)

        layoutParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(PREF_X, defaultX())
            y = prefs.getInt(PREF_Y, defaultY())
        }

        bubbleView.setOnTouchListener { _, event -> handleTouch(event) }

        windowManager.addView(bubbleView, layoutParams)
    }

    private fun observeState() {
        observerJob = serviceScope.launch {
            AppState.mediaProjectionActive.collectLatest { active ->
                updateBubbleAppearance(active)
                resizeBubble(active)
            }
        }
    }

    private fun updateBubbleAppearance(status: CaptureStatus) {
        val bubbleAppearance = controller.appearanceForStatus(status)

        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bubbleAppearance.colour)
        }
        bubbleView.setImageDrawable(drawable)
        bubbleView.alpha = bubbleAppearance.alpha
    }

    private fun resizeBubble(status: CaptureStatus) {
        val bubbleAppearance = controller.appearanceForStatus(status)
        val sizePx = dpToPx(bubbleAppearance.sizeDp)
        layoutParams.width  = sizePx
        layoutParams.height = sizePx
        windowManager.updateViewLayout(bubbleView, layoutParams)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragInitialX = layoutParams.x
                dragInitialY = layoutParams.y
                dragTouchX   = event.rawX
                dragTouchY   = event.rawY
                isDragging   = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - dragTouchX).roundToInt()
                val dy = (event.rawY - dragTouchY).roundToInt()
                // Only start dragging after a small threshold to avoid
                // accidental moves on tap
                if (!isDragging && (abs(dx) > 8 || abs(dy) > 8)) {
                    isDragging = true
                }
                if (isDragging) {
                    layoutParams.x = dragInitialX + dx
                    layoutParams.y = dragInitialY + dy
                    windowManager.updateViewLayout(bubbleView, layoutParams)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    // Save final position
                    prefs.edit()
                        .putInt(PREF_X, layoutParams.x)
                        .putInt(PREF_Y, layoutParams.y)
                        .apply()
                } else {
                    // Tap — no drag occurred
                    controller.onTap()
                }
                true
            }
            else -> false
        }
    }

    private val controller by lazy {
        BubbleController(this, AppState.mediaProjectionActive)
    }
    private fun defaultX(): Int {
        val display = windowManager.defaultDisplay
        val size    = android.graphics.Point()
        display.getSize(size)
        return size.x - dpToPx(controller.appearanceForStatus(CaptureStatus.INACTIVE).sizeDp) - dpToPx(8f)
    }

    private fun defaultY(): Int {
        val display = windowManager.defaultDisplay
        val size    = android.graphics.Point()
        display.getSize(size)
        return size.y / 2
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp,
            resources.displayMetrics
        ).roundToInt()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MHN Tracker Bubble",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MHN Tracker")
            .setContentText("Overlay active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onDestroy() {
        observerJob?.cancel()
        if (::bubbleView.isInitialized) {
            windowManager.removeView(bubbleView)
        }
        super.onDestroy()
    }
}