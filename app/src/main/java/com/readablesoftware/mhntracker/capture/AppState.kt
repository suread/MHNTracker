package com.readablesoftware.mhntracker.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide singleton holding MediaProjection active state.
 * Written by ScreenCaptureService, read by OverlayBubbleService.
 * Using an object (singleton) avoids needing a shared Application subclass.
 */
enum class CaptureStatus {
    INACTIVE,       // no MediaProjection
    ACTIVE,         // MediaProjection running, not in a fight
    IN_FIGHT,       // MediaProjection running, fight in progress
    FIGHT_TERMINATED // FightHandler onTerminate was called
}
object AppState {
    private val _mediaProjectionActive = MutableStateFlow(CaptureStatus.INACTIVE) // TODO - change name to reflect use of enum rather than boolean!
    val mediaProjectionActive: StateFlow<CaptureStatus> = _mediaProjectionActive

    fun setMediaProjectionActive(active: CaptureStatus) {
        _mediaProjectionActive.value = active
    }
}