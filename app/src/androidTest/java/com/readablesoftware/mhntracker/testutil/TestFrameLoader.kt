package com.readablesoftware.mhntracker.testutil

import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.BitmapFactory

object TestFrameLoader {
    fun loadTestFrame(directoryName: String, frameIndex: Int): Bitmap {
        val path = "frames/$directoryName/frame_${frameIndex.toString().padStart(4, '0')}.png"
        val context = InstrumentationRegistry.getInstrumentation().context
        return context.assets.open(path).use { stream ->
            BitmapFactory.decodeStream(stream)
                ?: error("Failed to decode bitmap from: $path")
        }
    }

}

