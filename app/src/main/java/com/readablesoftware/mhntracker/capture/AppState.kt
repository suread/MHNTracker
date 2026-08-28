package com.readablesoftware.mhntracker.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class CaptureStatus {
    INACTIVE,       // no MediaProjection
    ACTIVE,         // MediaProjection running, not in a fight
    IN_FIGHT,       // MediaProjection running, fight in progress
    FIGHT_TERMINATED // FightHandler onTerminate was called
}

/**
 * Process-wide singleton holding the current [CaptureStatus].
 * Written by ScreenCaptureService, read by OverlayBubbleService.
 * Using an object (singleton) avoids needing a shared Application subclass.
 */
object AppState {
    private val _captureStatus = MutableStateFlow(CaptureStatus.INACTIVE)
    val captureStatus: StateFlow<CaptureStatus> = _captureStatus

    fun setCaptureStatus(status: CaptureStatus) {
        _captureStatus.value = status
    }
}