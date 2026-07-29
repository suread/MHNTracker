package com.readablesoftware.mhntracker.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/**
 * Detects the start of a fight by recognising the grey banner text that
 * appears briefly at the beginning of an encounter ("Let's Hunt!" /
 * "Start Hunting...").
 *
 * Uses the same NCC template-matching approach as [HuntReportDetector].
 * [HuntReportDetector.bitmapToGrey] and [HuntReportDetector.ncc] are reused
 * directly — both are internal and visible within this package.
 *
 * TODO: no test coverage yet — validate BANNER_X1/Y1/X2/Y2 and
 * [NCC_THRESHOLD] against real fight-start recordings (positive and negative
 * frames), the way HuntReportDetectorTest does for [HuntReportDetector].
 */
class FightStartDetector private constructor(
    private val templatesGrey: List<FloatArray>,
    private val templateW:    Int,
    private val templateH:    Int,
) {

    companion object {

        // HuntReportDetector uses 0.85 with good separation between positive
        // and negative scores — reused here as a starting point, not yet
        // independently validated for this detector (no test coverage yet).
        const val NCC_THRESHOLD = 0.85f

        private val TEMPLATE_ASSETS = listOf("lets_hunt_template.png", "start_hunting_template.png")

        // Width/height match the template PNGs exactly (330x50). Not yet
        // validated against a real fight-start recording (no test coverage
        // yet) — origin (X1, Y1) in particular is unconfirmed.
        private const val BANNER_X1 = 365
        private const val BANNER_Y1 = 565
        private const val BANNER_X2 = 695
        private const val BANNER_Y2 = 615

        /**
         * Production constructor — loads template from app assets.
         */
        fun create(context: Context): FightStartDetector {
            val bitmaps = TEMPLATE_ASSETS.map { asset -> context.assets.open(asset).use { stream ->
                BitmapFactory.decodeStream(stream)
                    ?: error("Failed to decode $asset from assets")
            }}
            return fromBitmap(bitmaps)
        }

        /**
         * Test constructor — loads template from an absolute file path.
         * Mirrors [HuntReportDetector.createFromFile] for consistency.
         */
        fun createFromFile(paths: List<String>): FightStartDetector {
             val bitmaps = paths.map { path -> BitmapFactory.decodeFile(path)
                 ?: error("Failed to decode fight start template from: $path")}
             return fromBitmap(bitmaps)
        }

        private fun fromBitmap(bitmaps: List<Bitmap>): FightStartDetector {
            require(bitmaps.isNotEmpty()) { "Need at least one template in FightStartDetector" }
            require(bitmaps.all { it.width == bitmaps[0].width && it.height == bitmaps[0].height }) {
               "Templates must all be the same size in FightStartDetector"
            }
            val greys = bitmaps.map { bitmap -> HuntReportDetector.bitmapToGrey(bitmap) }
            return FightStartDetector(greys, bitmaps[0].width, bitmaps[0].height)
        }
    }

    /**
     * Returns true if the fight-start banner is visible in this frame.
     */
    fun isFightStartVisible(frame: Bitmap): Boolean {
        val cropW = BANNER_X2 - BANNER_X1
        val cropH = BANNER_Y2 - BANNER_Y1

        if (frame.width < BANNER_X2 || frame.height < BANNER_Y2) {
            Log.w("MHNDetect", "isFightStartVisible: frame too small " +
                    "(${frame.width}x${frame.height})")
            return false
        }

        if (cropW != templateW || cropH != templateH) {
            Log.w("MHNDetect", "isFightStartVisible: crop ${cropW}x${cropH} " +
                    "does not match template ${templateW}x${templateH}")
            return false
        }

        val crop     = Bitmap.createBitmap(frame, BANNER_X1, BANNER_Y1, cropW, cropH)
        val cropGrey = HuntReportDetector.bitmapToGrey(crop)
        val scores    = templatesGrey.map { templateGrey -> HuntReportDetector.ncc(templateGrey, cropGrey) }

        val scoresStr = scores.joinToString { "%.3f".format(it) }
        Log.d("MHNDetect", "isFightStartVisible: scores=$scoresStr threshold=$NCC_THRESHOLD")
        return scores.any { it >= NCC_THRESHOLD }
    }
}