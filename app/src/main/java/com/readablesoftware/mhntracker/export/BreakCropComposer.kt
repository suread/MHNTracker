package com.readablesoftware.mhntracker.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.core.graphics.createBitmap
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_TEXT_X1
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_TEXT_X2
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_TEXT_Y1
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_TEXT_Y2
import com.readablesoftware.mhntracker.util.ExportTimestamps
import java.io.File
import java.io.FileOutputStream

data class BitmapWithTime(
    val bitmap: Bitmap,
    val timestamp: Long
)

class BreakCropComposer {
    companion object {
        // Border margins around each crop when it is added to composite. Allows space to place
        // timestamp text and separation for readability if required for debug
        const val BORDER_TOP    = 20
        const val BORDER_BOTTOM = 40
        const val BORDER_COLOUR = Color.BLACK
        const val TEXT_COLOUR = Color.WHITE

    }

    private var crops = mutableListOf<BitmapWithTime>()

    fun addBreakFrame(frame: Bitmap, capturedAt: Long = System.currentTimeMillis()) {
        val crop = Bitmap.createBitmap(
            frame, BREAK_TEXT_X1, BREAK_TEXT_Y1,
            BREAK_TEXT_X2 - BREAK_TEXT_X1, BREAK_TEXT_Y2 - BREAK_TEXT_Y1
        )
        crops.add(BitmapWithTime(crop, capturedAt))
    }

    /**
     * Composes all buffered break crops into a single bitmap and saves it as a PNG.
     */
    fun exportComposite(sessionDir: File): File {
        val count = crops.size
        val width = BREAK_TEXT_X2 - BREAK_TEXT_X1
        val unitHeight = (BORDER_TOP + (BREAK_TEXT_Y2 - BREAK_TEXT_Y1) + BORDER_BOTTOM)
        val totalHeight = if (count > 0) count * unitHeight else BORDER_TOP

        Log.d("BreakCropComposer.exportComposite", "Number of break crops: $count")

        val output = createBitmap(width, totalHeight)
        val canvas = Canvas(output)
        canvas.drawColor(BORDER_COLOUR)

        for (i in 0 until crops.size) {
            val xStart = 0
            val yStart = (unitHeight * i + BORDER_TOP)
            val textX = 0
            val textY = (unitHeight * (i+1) - BORDER_BOTTOM) + 30
            canvas.drawBitmap(crops[i].bitmap, xStart.toFloat(), yStart.toFloat(), null)
            val paint = Paint().apply {
                color = TEXT_COLOUR
                textSize = 24f
                isAntiAlias = true
            }
            canvas.drawText(
                ExportTimestamps.format(crops[i].timestamp),
                textX.toFloat(), textY.toFloat(), paint)
            Log.d("BreakCropComposer.exportComposite", "crop index = $i; graphic location = ($xStart, $yStart); text location = ($textX, $textY)")

        }

        val dir = sessionDir
        dir.mkdirs()   // defensive: recreate if deleted mid-session
        val file = File(dir, "break_crops-${ExportTimestamps.format(System.currentTimeMillis())}.png")
        FileOutputStream(file).use { stream ->
            output.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }

        return file

    }
}