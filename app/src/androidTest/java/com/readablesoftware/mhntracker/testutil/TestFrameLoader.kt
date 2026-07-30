package com.readablesoftware.mhntracker.testutil

import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.BitmapFactory

object TestFrameLoader {
    fun loadTestFrame(directoryName: String, frameIndex: Int): Bitmap =
        loadTestFrame(directoryName, "frame_${frameIndex.toString().padStart(4, '0')}.png")

    fun loadTestFrame(directoryName: String, frameName: String): Bitmap {
        val path = "frames/$directoryName/$frameName"
        val context = InstrumentationRegistry.getInstrumentation().context
        return context.assets.open(path).use { stream ->
            BitmapFactory.decodeStream(stream)
                ?: error("Failed to decode bitmap from: $path")
        }
    }

}

