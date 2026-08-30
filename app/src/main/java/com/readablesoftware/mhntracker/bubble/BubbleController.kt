package com.readablesoftware.mhntracker.bubble

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.StateFlow
import androidx.core.graphics.toColorInt
import com.readablesoftware.mhntracker.capture.CaptureStatus
import com.readablesoftware.mhntracker.capture.ScreenCaptureService

class BubbleController(
    private val context: Context,
    private val mediaProjectionActive: StateFlow<CaptureStatus>,
) {
    companion object {
        private const val SIZE_INACTIVE_DP = 56f
        private const val SIZE_ACTIVE_DP   = 36f
        private const val ALPHA_INACTIVE   = 1.0f
        private const val ALPHA_ACTIVE     = 0.45f

        private val COLOUR_INACTIVE = "#FF8C00".toColorInt()
        private val COLOUR_IN_FIGHT = "#FF8C00".toColorInt()
        private val COLOUR_ACTIVE   = "#4CAF50".toColorInt()
        private val COLOUR_TERMINATED = "red".toColorInt()

    }

    fun onTap() {
        if (mediaProjectionActive.value == CaptureStatus.INACTIVE) {
            context.startActivity(
                Intent(context, PermissionTrampolineActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
       } else {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    fun appearanceForStatus(status: CaptureStatus): BubbleAppearance {
        return when (status) {
            CaptureStatus.INACTIVE -> BubbleAppearance(SIZE_INACTIVE_DP, COLOUR_INACTIVE, ALPHA_INACTIVE)
            CaptureStatus.ACTIVE -> BubbleAppearance(SIZE_ACTIVE_DP, COLOUR_ACTIVE, ALPHA_ACTIVE)
            CaptureStatus.IN_FIGHT -> BubbleAppearance(SIZE_ACTIVE_DP, COLOUR_IN_FIGHT, ALPHA_ACTIVE)
            CaptureStatus.FIGHT_TERMINATED -> BubbleAppearance(SIZE_ACTIVE_DP, COLOUR_TERMINATED, ALPHA_ACTIVE)
        }
    }
}

