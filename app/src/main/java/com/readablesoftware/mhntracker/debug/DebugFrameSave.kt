package com.readablesoftware.mhntracker.debug

import androidx.annotation.VisibleForTesting
import com.readablesoftware.mhntracker.BuildConfig

enum class FrameSaveFlow { BREAK, REPORT, RAW }

object DebugFrameSave {
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var enabled = setOf<FrameSaveFlow>(FrameSaveFlow.REPORT/* edit before building */)
    fun shouldSave(flow: FrameSaveFlow) = BuildConfig.DEBUG && flow in enabled
}