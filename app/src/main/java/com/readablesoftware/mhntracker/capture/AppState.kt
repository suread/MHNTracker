package com.readablesoftware.mhntracker.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide singleton holding MediaProjection active state.
 * Written by ScreenCaptureService, read by OverlayBubbleService.
 * Using an object (singleton) avoids needing a shared Application subclass.
 */
object AppState {
    private val _mediaProjectionActive = MutableStateFlow(false)
    val mediaProjectionActive: StateFlow<Boolean> = _mediaProjectionActive

    fun setMediaProjectionActive(active: Boolean) {
        _mediaProjectionActive.value = active
    }
}