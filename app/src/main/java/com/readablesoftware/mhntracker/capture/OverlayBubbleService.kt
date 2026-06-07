package com.readablesoftware.mhntracker.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import kotlin.math.roundToInt

class OverlayBubbleService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "mhn_bubble_channel"
        private const val PREFS_NAME = "bubble_prefs"
        private const val PREF_X = "bubble_x"
        private const val PREF_Y = "bubble_y"

        // Visual constants
        private const val SIZE_INACTIVE_DP = 56f
        private const val SIZE_ACTIVE_DP   = 36f
        private const val ALPHA_INACTIVE   = 1.0f
        private const val ALPHA_ACTIVE     = 0.45f

        // Orange when inactive (prominent), grey-green when active (unobtrusive)
        private val COLOUR_INACTIVE = Color.parseColor("#FF8C00")
        private val COLOUR_ACTIVE   = Color.parseColor("#4CAF50")
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
        prefs         = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

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
        updateBubbleAppearance(active = false)

        val sizePx = dpToPx(SIZE_INACTIVE_DP)

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

    private fun updateBubbleAppearance(active: Boolean) {
        val colour = if (active) COLOUR_ACTIVE else COLOUR_INACTIVE
        val alpha  = if (active) ALPHA_ACTIVE  else ALPHA_INACTIVE

        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colour)
        }
        bubbleView.setImageDrawable(drawable)
        bubbleView.alpha = alpha
    }

    private fun resizeBubble(active: Boolean) {
        val sizePx = dpToPx(if (active) SIZE_ACTIVE_DP else SIZE_INACTIVE_DP)
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
                if (!isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
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
                    handleTap()
                }
                true
            }
            else -> false
        }
    }

    private fun handleTap() {
        if (AppState.mediaProjectionActive.value) {
            // Stop capture
            stopService(Intent(this, ScreenCaptureService::class.java))
        } else {
            // Launch trampoline to request MediaProjection permission
            val intent = Intent(this, PermissionTrampolineActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    private fun defaultX(): Int {
        val display = windowManager.defaultDisplay
        val size    = android.graphics.Point()
        display.getSize(size)
        return size.x - dpToPx(SIZE_INACTIVE_DP) - dpToPx(8f)
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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