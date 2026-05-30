package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import com.readablesoftware.mhntracker.model.HuntResult

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
}