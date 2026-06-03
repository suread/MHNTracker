package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.readablesoftware.mhntracker.model.HuntResult
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.math.pow
import kotlin.math.sqrt
import android.util.Log

class HuntReportDetector(
    private val textDetector: TextDetector = MlKitTextDetector()  // default for production
) {

    suspend fun isHuntReportScreen(frame: Bitmap): Boolean {
        val t0 = System.currentTimeMillis()
        Log.d("MHN-timing", "isHuntReport start: ${System.currentTimeMillis() - t0}ms")

        val crop = Bitmap.createBitmap(  // crops to the "Hunt Report" text region
            frame,
            RewardsScreenConstants.HUNT_REPORT_X1,
            RewardsScreenConstants.HUNT_REPORT_Y1,
            RewardsScreenConstants.HUNT_REPORT_X2 - RewardsScreenConstants.HUNT_REPORT_X1,
            RewardsScreenConstants.HUNT_REPORT_Y2 - RewardsScreenConstants.HUNT_REPORT_Y1,
        )
        Log.d("MHN-timing", "isHuntReport crop made: ${System.currentTimeMillis() - t0}ms")
        // image used for MLKit must be at least 32x32 - so if scaling takes us below that it will fail
        val result1 = textDetector.detectText(crop)
        Log.d("MHN-timing", "isHuntReport text detected: ${System.currentTimeMillis() - t0}ms")
        Log.d("MHN-text", result1)
        val result2 = result1.contains("Hunt Report", ignoreCase = true)
        Log.d("MHN-timing", "isHuntReport text contents: ${System.currentTimeMillis() - t0}ms")

        return result2
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