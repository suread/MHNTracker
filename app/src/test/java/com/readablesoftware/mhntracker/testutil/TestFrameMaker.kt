package com.readablesoftware.mhntracker.testutil

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import android.graphics.Color
import android.graphics.Paint

data class FrameMarker (
    val x1: Int, // x - top left corner
    val y1: Int, // y - top left corner
    val x2: Int, // x - bottom right corner
    val y2: Int, // y - bottom right corner
    val colour: Int
)

object TestFrameMaker {

    // make frame sized to Pixel 7 screen, with plain background and markers placed in colour/coordinates given in markers List
    fun pixel7(markers: List<FrameMarker>, background: Int = Color.BLACK): Bitmap {
        return makeFrame(1080, 2400, markers, background)
    }

    fun makeFrame(
        width: Int,
        height: Int,
        markers: List<FrameMarker> = listOf(),
        background: Int = Color.BLACK
    ): Bitmap {
        val frame = createBitmap(width, height)
        Canvas(frame).apply {
            drawColor(background)
            for (f in markers) {
                val paint = Paint().apply { color = f.colour }
                drawRect(
                    f.x1.toFloat(),
                    f.y1.toFloat(),
                    f.x2.toFloat(),
                    f.y2.toFloat(),
                    paint
                )
            }
        }
        return frame
    }
}

