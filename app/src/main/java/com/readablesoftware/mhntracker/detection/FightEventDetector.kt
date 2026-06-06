package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.util.Log
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_X1
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_Y1
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_X2
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_Y2

class FightEventDetector {

    companion object {
        // Orange pixel definition — empirically derived from Radobaan and Tzitzi
        // BREAK frames against dark and light backgrounds respectively.
        // The BREAK text fill is consistently high-R, medium-G, low-B,
        // with R clearly greater than G.
        private const val ORANGE_R_MIN  = 150
        private const val ORANGE_G_MIN  = 80
        private const val ORANGE_B_MAX  = 120
        private const val ORANGE_RG_GAP = 40   // minimum R - G

        // Fraction of crop pixels that must be orange for a positive detection.
        // Fully rendered BREAK frames: ~0.43–0.46. Threshold of 0.20 gives
        // comfortable margin while excluding combat effects and other screens.
        const val FRAC_THRESHOLD = 0.20f

        // Fraction of crop columns that must contain at least one orange pixel.
        // Discriminates the wide BREAK text (~0.95) from narrow combat effects.
        // Animation frames score ~0.25; threshold of 0.70 excludes them cleanly.
        const val COV_THRESHOLD = 0.70f
    }

    /**
     * Returns true if the fully rendered "BREAK" graphic is visible in the
     * given frame.
     *
     * Detection is based on two signals in the fixed BREAK crop region:
     *   1. Orange pixel fraction — the BREAK text fill colour is consistently
     *      orange regardless of the background environment.
     *   2. Column coverage — the text spans most of the crop width, unlike
     *      narrow combat effects that may contain orange pixels locally.
     *
     * Both signals must exceed their thresholds for a positive result.
     *
     * Faded animation frames (BREAK scaling in) do not reliably exceed the
     * thresholds and will typically return false. This is acceptable — the
     * part name is not yet visible on those frames so saving them adds no value.
     *
     * False positives from non-fight screens (desert biome map visible in the
     * crop region, certain UI screens) are known and accepted at this stage.
     * Screen-type filtering to suppress non-fight detection is a future concern.
     */
    fun isBreakVisible(frame: Bitmap): Boolean {
        val t0 = System.currentTimeMillis()

        if (frame.width < BREAK_X2 || frame.height < BREAK_Y2) {
            Log.w("MHNDetect", "isBreakVisible: frame too small (${frame.width}x${frame.height})")
            return false
        }

        val cropW  = BREAK_X2 - BREAK_X1
        val cropH  = BREAK_Y2 - BREAK_Y1
        val pixels = IntArray(cropW * cropH)

        // Read pixels directly — no Bitmap.createBitmap crop needed.
        // getPixels extracts the region in one call, avoiding an intermediate
        // Bitmap allocation.
        frame.getPixels(pixels, 0, cropW, BREAK_X1, BREAK_Y1, cropW, cropH)

        var orangeCount  = 0
        val colHasOrange = BooleanArray(cropW)

        for (i in pixels.indices) {
            val px = pixels[i]
            val r  = (px shr 16) and 0xFF
            val g  = (px shr 8)  and 0xFF
            val b  =  px         and 0xFF

            if (r > ORANGE_R_MIN &&
                g > ORANGE_G_MIN &&
                b < ORANGE_B_MAX &&
                (r - g) > ORANGE_RG_GAP) {
                orangeCount++
                colHasOrange[i % cropW] = true
            }
        }

        val total        = pixels.size
        val orangeFrac   = orangeCount.toFloat() / total
        val colCoverage  = colHasOrange.count { it }.toFloat() / cropW

        val result = orangeFrac >= FRAC_THRESHOLD && colCoverage >= COV_THRESHOLD

        Log.d("MHNDetect", "isBreakVisible: frac=${"%.3f".format(orangeFrac)} " +
                "cov=${"%.3f".format(colCoverage)} " +
                "result=$result " +
                "time=${System.currentTimeMillis() - t0}ms")

        return result
    }
}