package com.readablesoftware.mhntracker.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import com.readablesoftware.mhntracker.detection.RewardsScreenConstants.STATUS_AREA_HEIGHT
import com.readablesoftware.mhntracker.util.ExportTimestamps
import java.io.File
import java.io.FileOutputStream

data class BitmapWithVerticalOffset(
    val bitmap: Bitmap,
    val scroll: Int
)

class HuntReportComposer {

    private var huntReportScrolls = mutableListOf<BitmapWithVerticalOffset>()

    fun addFrame(frame: Bitmap) {
        if (huntReportScrolls.size > 0 && frame.width != huntReportScrolls[0].bitmap.width) {
            throw IllegalArgumentException("Hunt report frames must all be same width")
        }
        if (frame.height <= STATUS_AREA_HEIGHT) {
            throw IllegalArgumentException("Hunt report frame must have height greater than area cropped for status bar")
        }
        huntReportScrolls.add(BitmapWithVerticalOffset(frame, 0))
    }
    /**
     * build contents of huntReportCrops into single bitmap and save to png
     */
    fun exportComposite(sessionDir: File): File {
        if (huntReportScrolls.size == 0) {
            return exportHuntReportFail(sessionDir)
        }
        val firstFrame = huntReportScrolls.removeAt(0).bitmap
        var composite = createBitmap(firstFrame.width, firstFrame.height - STATUS_AREA_HEIGHT)
        val canvas = Canvas(composite)
        val srcRect = Rect(0, STATUS_AREA_HEIGHT, firstFrame.width, firstFrame.height)
        val dstRect = Rect(0, 0, firstFrame.width, firstFrame.height - STATUS_AREA_HEIGHT)
        canvas.drawBitmap(firstFrame, srcRect, dstRect, null)

        for (f in huntReportScrolls) {
            val newComposite = createBitmap(composite.width, composite.height + f.bitmap.height - STATUS_AREA_HEIGHT)
            val newCanvas = Canvas(newComposite)
            val src1 = Rect(0, 0, composite.width, composite.height)
            val src2 = Rect(0, STATUS_AREA_HEIGHT, f.bitmap.width, f.bitmap.height)
            val dst1  = Rect(0, 0, composite.width, composite.height)
            val dst2 = Rect(0, newComposite.height - (f.bitmap.height - STATUS_AREA_HEIGHT), newComposite.width, newComposite.height)
            newCanvas.drawBitmap(composite, src1, dst1, null)
            newCanvas.drawBitmap(f.bitmap, src2, dst2, null)

            composite = newComposite
        }
        val dir = sessionDir
        dir.mkdirs()   // defensive: recreate if deleted mid-session
        val file = File(dir, "hunt_report-${ExportTimestamps.format(System.currentTimeMillis())}.png")
        FileOutputStream(file).use { stream ->
            composite.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }

        return file

    }

    // if no hunt report frames have been stored, return small bitmap, solid colour
    private fun exportHuntReportFail(sessionDir: File): File {
        // TODO is there any reason to size it differently, e.g. width of expected frame size?
        val bitmap = createBitmap(100,20)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val dir = sessionDir
        dir.mkdirs()   // defensive: recreate if deleted mid-session
        val file = File(dir, "hunt_report-${ExportTimestamps.format(System.currentTimeMillis())}.png")
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }

        return file
    }
}