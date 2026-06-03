package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_X1
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_Y1
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_X2
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_Y2

class FightEventDetector(
    private val textDetector: TextDetector = MlKitTextDetector()
) {

    /**
     * Returns true if the "BREAK" graphic is visible in the given frame.
     *
     * Crops the frame to the fixed BREAK region and delegates to the
     * TextDetector. The variable part label below BREAK ("Body",
     * "Head (1st Time)", etc.) is intentionally outside this crop —
     * it is captured by saving the full frame, not parsed here.
     *
     * Detection is case-insensitive to be robust against ML Kit returning
     * mixed-case results from the stylised font.
     */
    suspend fun isBreakVisible(frame: Bitmap): Boolean {
        val crop = Bitmap.createBitmap(
            frame,
            BREAK_X1,
            BREAK_Y1,
            BREAK_X2 - BREAK_X1,
            BREAK_Y2 - BREAK_Y1
        )
        val text = textDetector.detectText(crop)
        return text.contains("BREAK", ignoreCase = true)
    }
}
