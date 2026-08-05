package com.readablesoftware.mhntracker.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import com.readablesoftware.mhntracker.detection.RewardsScreenConstants.STATUS_AREA_HEIGHT
import com.readablesoftware.mhntracker.util.ExportTimestamps
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

data class BitmapWithVerticalOffset(
    val bitmap: Bitmap,
    val scroll: Int
)

data class ShiftResult(
    val offset: Int,
    val accepted: Boolean
)

data class MeasuredShift(val offset: Int, val confidence: Double)

typealias ShiftMeasurer =
            (frameA: Bitmap, frameB: Bitmap, xLeft: Int, xRight: Int, yTop: Int, height: Int, maxShift: Int) -> MeasuredShift

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
     * Composes all buffered hunt report frames into a single stitched bitmap
     * and saves it as a PNG.
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

    /**
     * Finds the vertical scroll offset between [frameA] and [frameB] by locating a template
     * region cropped from frameA within frameB, retrying at successively lower template
     * positions until a confident match is found.
     *
     * The template starts at ([x1], [yTopStart]) to ([x2], [yTopStart] + [height]) in frameA.
     * [measure] searches for it within frameB. If the match confidence falls below
     * [confidenceThreshold], the template position moves down by [step] and the search retries -
     * low confidence usually means the template landed on unrendered or animating content, not
     * that the true shift was measured wrong. The first attempt reaching [confidenceThreshold] is
     * accepted and returned immediately.
     *
     * If no attempt reaches [confidenceThreshold] before the template would cross [bottomMargin]
     * from the bottom of frameA, returns accepted = false and offset = 0 - the caller should
     * discard frameB rather than trust an unreliable offset.
     *
     * [measure] defaults to the real [measureShift] implementation; tests can substitute a fake
     * to exercise the retry/threshold logic without real template matching.
     */

    fun findShift(frameA: Bitmap, frameB: Bitmap, x1: Int, x2: Int, yTopStart: Int, height: Int, maxShift: Int, step: Int, bottomMargin: Int, confidenceThreshold: Double, measure: ShiftMeasurer = ::measureShift): ShiftResult {
        var yTop = yTopStart

        while (true) {
            val result = measure(frameA, frameB, x1, x2, yTop, height, maxShift)

            if (result.confidence > confidenceThreshold) {
                return ShiftResult(result.offset, true)
            }

            yTop += step
            if (yTop + height > frameA.height - bottomMargin) {
                return ShiftResult(0, false)
            }
        }
    }

    /**
     * Estimates the vertical scroll shift between [frameA] (older) and [frameB]
     * (newer), using column [x1]:[x2] as the comparison region.
     *
     * Crops a template of [height] from [frameA] at [yTop], then searches for
     * it in [frameB] within a taller region starting further up — content that
     * has scrolled up appears higher (smaller y) in [frameB] than it was in
     * [frameA].
     */
    fun measureShift(frameA: Bitmap, frameB: Bitmap, x1: Int, x2: Int, yTop: Int, height: Int, maxShift: Int): MeasuredShift {
        val matA = Mat()
        Utils.bitmapToMat(frameA, matA)
        val matB = Mat()
        Utils.bitmapToMat(frameB, matB)

        val template = matA.submat(yTop, yTop+height, x1, x2)
        val searchTop = maxOf(0, yTop - maxShift)
        val search = matB.submat(searchTop, yTop+height, x1, x2)

        val result = Mat()
        Imgproc.matchTemplate(search, template, result, Imgproc.TM_CCOEFF_NORMED)
        val mmr = Core.minMaxLoc(result)

        check(mmr.maxLoc.x == 0.0) { "template not found at left side of search crop" }

        return MeasuredShift((yTop - searchTop) - mmr.maxLoc.y.toInt(), mmr.maxVal)
    }

}