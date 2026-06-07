package com.readablesoftware.mhntracker.capture

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

class BubbleController(
    private val context: Context,
    private val mediaProjectionActive: StateFlow<Boolean>,
) {
    fun onTap() {
        if (mediaProjectionActive.value) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        } else {
            context.startActivity(
                Intent(context, PermissionTrampolineActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}