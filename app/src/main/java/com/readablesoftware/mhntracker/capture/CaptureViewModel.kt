package com.readablesoftware.mhntracker.capture

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class CaptureState {
    STOPPED,
    WAITING,
    CAPTURING
}

class CaptureViewModel : ViewModel() {

    private val _state = MutableStateFlow(CaptureState.STOPPED)
    val state: StateFlow<CaptureState> = _state

    fun updateState(newState: CaptureState) {
        _state.value = newState
    }
}