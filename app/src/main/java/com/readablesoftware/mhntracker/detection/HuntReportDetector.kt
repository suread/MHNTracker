package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import com.readablesoftware.mhntracker.model.HuntResult
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.math.pow
import kotlin.math.sqrt
class HuntReportDetector(
    private val textDetector: TextDetector = MlKitTextDetector()  // default for production
) {

    fun isHuntReportScreen(frame: Bitmap): Boolean {
        val crop = Bitmap.createBitmap(  // crops to the "Hunt Report" text region
            frame,
            RewardsScreenConstants.HUNT_REPORT_X1,
            RewardsScreenConstants.HUNT_REPORT_Y1,
            RewardsScreenConstants.HUNT_REPORT_X2 - RewardsScreenConstants.HUNT_REPORT_X1,
            RewardsScreenConstants.HUNT_REPORT_Y2 - RewardsScreenConstants.HUNT_REPORT_Y1,
        )
        return textDetector.detectText(crop).contains("Hunt Report", ignoreCase = true)
    }

    fun process(frames: List<Bitmap>): HuntResult? {
        TODO("Not yet implemented")
    }

    fun isConfirmButtonVisible(frame: Bitmap): Boolean {
        val sampleW = RewardsScreenConstants.CONFIRM_SAMPLE_X2 - RewardsScreenConstants.CONFIRM_SAMPLE_X1
        val sampleH = RewardsScreenConstants.CONFIRM_SAMPLE_Y2 - RewardsScreenConstants.CONFIRM_SAMPLE_Y1

        val pixels = IntArray(sampleW * sampleH)
        frame.getPixels(
            pixels,
            0,
            sampleW,
            RewardsScreenConstants.CONFIRM_SAMPLE_X1,
            RewardsScreenConstants.CONFIRM_SAMPLE_Y1,
            sampleW,
            sampleH,
        )

        // Compute mean RGB from ARGB packed integers
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        for (pixel in pixels) {
            totalR += (pixel shr 16) and 0xFF
            totalG += (pixel shr 8) and 0xFF
            totalB += pixel and 0xFF
        }

        val count = pixels.size
        val meanR = totalR.toDouble() / count
        val meanG = totalG.toDouble() / count
        val meanB = totalB.toDouble() / count

        // Constants are BGR, convert to RGB for comparison
        val targetR = RewardsScreenConstants.CONFIRM_BGR[2].toDouble()
        val targetG = RewardsScreenConstants.CONFIRM_BGR[1].toDouble()
        val targetB = RewardsScreenConstants.CONFIRM_BGR[0].toDouble()

        val distance = sqrt(
            (meanR - targetR).pow(2) +
                    (meanG - targetG).pow(2) +
                    (meanB - targetB).pow(2)
        )

        return distance < RewardsScreenConstants.CONFIRM_COLOUR_TOLERANCE
    }

    fun debugSampleRegion(frame: Bitmap): String {
        val sampleW = RewardsScreenConstants.CONFIRM_SAMPLE_X2 - RewardsScreenConstants.CONFIRM_SAMPLE_X1
        val sampleH = RewardsScreenConstants.CONFIRM_SAMPLE_Y2 - RewardsScreenConstants.CONFIRM_SAMPLE_Y1

        val pixels = IntArray(sampleW * sampleH)
        frame.getPixels(
            pixels,
            0,
            sampleW,
            RewardsScreenConstants.CONFIRM_SAMPLE_X1,
            RewardsScreenConstants.CONFIRM_SAMPLE_Y1,
            sampleW,
            sampleH,
        )

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        for (pixel in pixels) {
            totalR += (pixel shr 16) and 0xFF
            totalG += (pixel shr 8) and 0xFF
            totalB += pixel and 0xFF
        }

        val count = pixels.size
        val meanR = totalR.toDouble() / count
        val meanG = totalG.toDouble() / count
        val meanB = totalB.toDouble() / count

        val targetR = RewardsScreenConstants.CONFIRM_BGR[2].toDouble()
        val targetG = RewardsScreenConstants.CONFIRM_BGR[1].toDouble()
        val targetB = RewardsScreenConstants.CONFIRM_BGR[0].toDouble()

        val distance = sqrt(
            (meanR - targetR).pow(2) +
                    (meanG - targetG).pow(2) +
                    (meanB - targetB).pow(2)
        )

        return "mean RGB=($meanR, $meanG, $meanB) " +
                "target RGB=($targetR, $targetG, $targetB) " +
                "distance=$distance " +
                "tolerance=${RewardsScreenConstants.CONFIRM_COLOUR_TOLERANCE}"
    }
}