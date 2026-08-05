package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class RgbSample(
    val r: Double,
    val g: Double,
    val b: Double,
)

/**
 * Detects screen-wide signals that the router needs regardless of which
 * session handler is active:
 *
 *   - Black screen: user has left the app or the screen has turned off while
 *     MediaProjection is still running. Used as a per-frame pre-filter —
 *     if the screen is black, no further detection work is performed.
 *
 *   - Map screen: the in-game map is visible, indicating that whatever
 *     activity was in progress has ended (fight aborted, session abandoned,
 *     etc.). Used as a general escape signal: the router terminates any
 *     active handler when the map is detected.
 *
 * Both checks use Bitmap.getPixels() — no native library dependency, works
 * under Robolectric.
 */
class AppStateDetector {

    private enum class PointType { RED, BROWN, NEEDLE, CREAM, OTHER }
    companion object {

        // ── Black screen ──────────────────────────────────────────────────
        // Sample a region near screen centre — avoids notification bar and
        // navigation bar which may not be fully black even when MHN is not
        // in the foreground.
        // Pixel 7 (1080×2400): centre of the game viewport.
        private const val BLACK_SAMPLE_X1 = 400
        private const val BLACK_SAMPLE_Y1 = 900
        private const val BLACK_SAMPLE_X2 = 680
        private const val BLACK_SAMPLE_Y2 = 1100

        // All-channel mean must be below this value for the screen to be
        // considered black. Near-black UI elements (dark backgrounds during
        // loading) typically average 20–30; true black screens are <5.
        // 15 gives comfortable separation.
        private const val BLACK_MEAN_THRESHOLD = 15.0

        // ── Map screen ────────────────────────────────────────────────────
        // The compass circle is a fixed light-grey filled circle present on
        // both map variants (world map and area map). Its position is the
        // same in both variants; other UI elements differ.
        //
        // Detection: sample the interior of the compass circle for high
        // brightness (light grey fill) and low saturation (grey, not coloured).
        // The compass needle rotates but occupies a small fraction of the
        // circle interior and does not affect the mean significantly.
        //
        private const val MAP_SAMPLE_X1 = 971
        private const val MAP_SAMPLE_Y1 = 205
        private const val MAP_SAMPLE_X2 = 1055
        private const val MAP_SAMPLE_Y2 = 290

        // Sampled region mean must exceed this brightness to confirm grey fill.
        private const val MAP_BRIGHT_MIN = 180

        // Max difference between R and B channel means — keeps the check
        // specific to grey (low saturation) rather than any bright colour.
        private const val MAP_SAT_MAX = 25

        private const val RING_GB_MIN = 230
        private const val NEEDLE_GB_MAX = 90
        private const val COMPASS_CENTRE_X = 1013
        private const val COMPASS_CENTRE_Y = 248
        private const val RING_RADIUS = 30
    }

    /**
     * Returns true if the screen is effectively black.
     *
     * Cheap: one getPixels call on a ~280×200px region, mean of all channels.
     * Intended to run on every frame as a pre-filter before any other detection.
     */
    fun isBlackScreen(frame: Bitmap): Boolean {
        if (frame.width < BLACK_SAMPLE_X2 || frame.height < BLACK_SAMPLE_Y2) {
            Log.w("MHNDetect", "isBlackScreen: frame too small (${frame.width}x${frame.height})")
            return false
        }

        val w      = BLACK_SAMPLE_X2 - BLACK_SAMPLE_X1
        val h      = BLACK_SAMPLE_Y2 - BLACK_SAMPLE_Y1
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, BLACK_SAMPLE_X1, BLACK_SAMPLE_Y1, w, h)

        var total = 0L
        for (px in pixels) {
            total += (px shr 16) and 0xFF   // R
            total += (px shr 8)  and 0xFF   // G
            total +=  px         and 0xFF   // B
        }
        val mean = total.toDouble() / (pixels.size * 3)

        Log.d("MHNDetect", "isBlackScreen: mean=${"%.1f".format(mean)} threshold=$BLACK_MEAN_THRESHOLD")
        return mean < BLACK_MEAN_THRESHOLD
    }

    /**
     * Returns true if the in-game map screen is visible.
     *
     * Detects the compass circle that is present in the same position on both
     * map variants (world map and area map). Both variants are treated as a
     * map detection — the router uses this as a general "activity ended" signal
     * regardless of which map variant is showing.
     *
     */
    fun isMapScreen(frame: Bitmap): Boolean {

        if (frame.width < COMPASS_CENTRE_X + RING_RADIUS + 1 || frame.height < COMPASS_CENTRE_Y + RING_RADIUS + 1) {
            Log.w("MHNDetect", "isMapScreen: frame too small (${frame.width}x${frame.height})")
            return false
        }

        // TODO: only sample ring if centre matches (skipped for now to log both during calibration)
        val isCompassCentre = isValidCompassCentre(frame)
        val isCompassRing = isValidCompassRing(frame)
        val result = isCompassCentre && isCompassRing

        Log.d("MHNDetect", "isMapScreen: compass centre = $isCompassCentre, " +
                "compass ring = $isCompassRing")
        return result
    }

    private fun isValidCompassCentre(frame: Bitmap): Boolean {
        val averageRgb = averageSample(frame, COMPASS_CENTRE_X, COMPASS_CENTRE_Y, 5)
        val pointType = classifyPoint(averageRgb)

        // pointType expected to be NEEDLE as we are averaging across a mix of red + brown, but red/brown would be acceptable
        return pointType == PointType.NEEDLE || pointType == PointType.RED || pointType == PointType.BROWN
    }

    private fun isValidCompassRing(frame: Bitmap): Boolean {
        val ringSamples = (0 until 8).map { i ->
            val angle = Math.toRadians(i * 45.0)
            val px = COMPASS_CENTRE_X + (RING_RADIUS * cos(angle)).roundToInt()
            val py = COMPASS_CENTRE_Y + (RING_RADIUS * sin(angle)).roundToInt()
            // expression for IDE watch to check sample: Bitmap.createBitmap(frame, px, py, 3, 3)
            averageSample(frame, px, py, halfSize = 1)
        }

        return isValidCompassRing(ringSamples)
    }

    private fun isValidCompassRing(points: List<RgbSample>): Boolean {
        val pointClassification = points.map {
            classifyPoint(it)
        }

        Log.d("MHNDetect", "isValidCompassRing: PointTypes=$pointClassification")

        val countCream = pointClassification.count { it == PointType.CREAM }
        if (countCream < 6) return false
        if (countCream >= 7) return true // edges of needle may classify as OTHER or CREAM due to anti-aliasing
        // leaving only cases where countCream is 6 - we require the non-cream points to be opposite sides of the ring
        for (i in 0..3) {
            val testList = listOf(pointClassification[i], pointClassification[i+4])
            if (
                testList.count { it == PointType.CREAM } == 0 &&
                testList.count { it == PointType.RED } <= 1 &&
                testList.count { it == PointType.BROWN } <= 1
                ) {
                return true
            }
        }

        return false
    }

    private fun classifyPoint(sample: RgbSample): PointType {
        val isCream = sample.g > RING_GB_MIN && sample.b > RING_GB_MIN
        val isNeedle = sample.g < NEEDLE_GB_MAX && sample.b < NEEDLE_GB_MAX
        val isRed = isNeedle && sample.r > 180
        val isBrown = isNeedle && sample.r <= 110
        return when {
            isRed    -> PointType.RED
            isBrown  -> PointType.BROWN
            isNeedle -> PointType.NEEDLE
            isCream  -> PointType.CREAM
            else     -> PointType.OTHER
        }
    }

    private fun averageSample(frame: Bitmap, centreX: Int, centreY: Int, halfSize: Int): RgbSample {
        val size = halfSize * 2 + 1
        val x0 = centreX - halfSize
        val y0 = centreY - halfSize
        val pixels = IntArray(size * size)
        frame.getPixels(pixels, 0, size, x0, y0, size, size)

        var totalR = 0L;  var totalG = 0L;  var totalB = 0L
        for (px in pixels) {
            totalR += (px shr 16) and 0xFF
            totalG += (px shr 8)  and 0xFF
            totalB +=  px         and 0xFF
        }

        val count  = pixels.size

        return RgbSample(totalR.toDouble() / count, totalG.toDouble() / count, totalB.toDouble() / count)
    }
}