package com.readablesoftware.mhntracker.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.readablesoftware.mhntracker.model.HuntResult
import kotlin.math.pow
import kotlin.math.sqrt
import android.util.Log

class HuntReportDetector private constructor(
    private val templateGrey: FloatArray,   // greyscale pixel values, row-major
    private val templateW:    Int,
    private val templateH:    Int,
    private val rewardsTemplateGrey: FloatArray,
    private val rewardsTemplateW:    Int,
    private val rewardsTemplateH:    Int,
) {

    companion object {
        // NCC score at or above this value is treated as a positive detection.
        // Empirically determined: positives cluster above 0.9, nearest false
        // positive ("Hunter" profile page title) observed at ~0.5. Gap of ~0.35
        // gives comfortable margin. Revisit if new languages or game screens
        // produce scores above 0.6.
        const val NCC_THRESHOLD = 0.85f

        private const val TEMPLATE_ASSET = "hunt_report_template.png"
        private const val REWARDS_TEMPLATE_ASSET = "rewards_template.png"

        // Production constructor — loads templates from app assets.
        fun create(context: Context): HuntReportDetector {
            val bitmap = context.assets.open(TEMPLATE_ASSET).use { stream ->
                BitmapFactory.decodeStream(stream)
                    ?: error("Failed to decode $TEMPLATE_ASSET from assets")
            }
            val rewardsBitmap = context.assets.open(REWARDS_TEMPLATE_ASSET).use { stream ->
                BitmapFactory.decodeStream(stream)
                    ?: error("Failed to decode $REWARDS_TEMPLATE_ASSET from assets")
            }
            return fromBitmaps(bitmap, rewardsBitmap)
        }

        // Test constructor — loads templates from absolute file paths.
        // Use this in Robolectric tests to load directly from
        // src/main/assets/ without duplicating the asset files.
        // Example paths: "src/main/assets/hunt_report_template.png",
        // "src/main/assets/rewards_template.png"
        fun createFromFile(path: String, rewardsPath: String): HuntReportDetector {
            val bitmap = BitmapFactory.decodeFile(path)
                ?: error("Failed to decode template from file: $path")
            val rewardsBitmap = BitmapFactory.decodeFile(rewardsPath)
                ?: error("Failed to decode template from file: $rewardsPath")
            return fromBitmaps(bitmap, rewardsBitmap)
        }

        private fun fromBitmaps(bitmap: Bitmap, rewardsBitmap: Bitmap): HuntReportDetector {
            val grey        = bitmapToGrey(bitmap)
            val rewardsGrey = bitmapToGrey(rewardsBitmap)
            return HuntReportDetector(
                grey, bitmap.width, bitmap.height,
                rewardsGrey, rewardsBitmap.width, rewardsBitmap.height,
            )
        }

        // Extract greyscale (mean of R, G, B) from a Bitmap into a FloatArray.
        // Uses Bitmap.getPixels() — pure Android API, works under Robolectric.
        internal fun bitmapToGrey(bitmap: Bitmap): FloatArray {
            val w      = bitmap.width
            val h      = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            return FloatArray(w * h) { i ->
                val px = pixels[i]
                val r  = (px shr 16) and 0xFF
                val g  = (px shr 8)  and 0xFF
                val b  =  px         and 0xFF
                (r + g + b) / 3f
            }
        }

        // NCC between two same-length float arrays.
        // Returns 0f if either array has zero variance (flat region).
        internal fun ncc(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "ncc: arrays must be same length" }
            val n    = a.size
            var sumA = 0f;  var sumB = 0f
            for (i in 0 until n) { sumA += a[i];  sumB += b[i] }
            val meanA = sumA / n
            val meanB = sumB / n

            var dot  = 0f;  var normA = 0f;  var normB = 0f
            for (i in 0 until n) {
                val da = a[i] - meanA
                val db = b[i] - meanB
                dot   += da * db
                normA += da * da
                normB += db * db
            }
            val denom = sqrt(normA) * sqrt(normB)
            return if (denom < 1e-6f) 0f else dot / denom
        }
    }

    // -----------------------------------------------------------------------
    // Detection
    // -----------------------------------------------------------------------

    /**
     * Returns true if either the "Hunt Report" title or the "Rewards" section
     * divider is visible. Two independent triggers for the same screen:
     * the title can be obscured by stacked pop-ups (quest/event toasts) for
     * long enough that it scrolls away unseen, whereas "Rewards" sits lower
     * on the same static screen and is unaffected by that overlap.
     */
    fun isHuntReportScreen(frame: Bitmap): Boolean {
        return isTitleVisible(frame) || isRewardsHeaderVisible(frame)
    }

    /**
     * Checks the "Hunt Report" title independently of [isRewardsHeaderVisible].
     * Exposed (not private) so callers can tell which signal fired — used by
     * [FightHandler] to log cases where the title fires but Rewards never
     * does during the same capture, to gauge whether Rewards alone would be
     * a safe sole trigger.
     */
    fun isTitleVisible(frame: Bitmap): Boolean {
        val t0 = System.currentTimeMillis()

        val cropW = RewardsScreenConstants.HUNT_REPORT_X2 - RewardsScreenConstants.HUNT_REPORT_X1
        val cropH = RewardsScreenConstants.HUNT_REPORT_Y2 - RewardsScreenConstants.HUNT_REPORT_Y1

        if (frame.width < RewardsScreenConstants.HUNT_REPORT_X2 ||
            frame.height < RewardsScreenConstants.HUNT_REPORT_Y2) {
            Log.w("MHNDetect", "isTitleVisible: frame too small (${frame.width}x${frame.height})")
            return false
        }

        if (cropW != templateW || cropH != templateH) {
            Log.w("MHNDetect", "isTitleVisible: crop ${cropW}x${cropH} " +
                    "does not match template ${templateW}x${templateH}")
            return false
        }

        val crop     = Bitmap.createBitmap(
            frame,
            RewardsScreenConstants.HUNT_REPORT_X1,
            RewardsScreenConstants.HUNT_REPORT_Y1,
            cropW,
            cropH,
        )
        val cropGrey = bitmapToGrey(crop)
        val score    = ncc(templateGrey, cropGrey)

        Log.d("MHNDetect", "isTitleVisible: score=$score threshold=$NCC_THRESHOLD " +
                "time=${System.currentTimeMillis() - t0}ms")

        return score >= NCC_THRESHOLD
    }

    /** Checks the "Rewards" section divider independently of [isTitleVisible]. */
    fun isRewardsHeaderVisible(frame: Bitmap): Boolean {
        val t0 = System.currentTimeMillis()

        val cropW = RewardsScreenConstants.REWARDS_X2 - RewardsScreenConstants.REWARDS_X1
        val cropH = RewardsScreenConstants.REWARDS_Y2 - RewardsScreenConstants.REWARDS_Y1

        if (frame.width < RewardsScreenConstants.REWARDS_X2 ||
            frame.height < RewardsScreenConstants.REWARDS_Y2) {
            Log.w("MHNDetect", "isRewardsHeaderVisible: frame too small (${frame.width}x${frame.height})")
            return false
        }

        if (cropW != rewardsTemplateW || cropH != rewardsTemplateH) {
            Log.w("MHNDetect", "isRewardsHeaderVisible: crop ${cropW}x${cropH} " +
                    "does not match template ${rewardsTemplateW}x${rewardsTemplateH}")
            return false
        }

        val crop     = Bitmap.createBitmap(
            frame,
            RewardsScreenConstants.REWARDS_X1,
            RewardsScreenConstants.REWARDS_Y1,
            cropW,
            cropH,
        )
        val cropGrey = bitmapToGrey(crop)
        val score    = ncc(rewardsTemplateGrey, cropGrey)

        Log.d("MHNDetect", "isRewardsHeaderVisible: score=$score threshold=$NCC_THRESHOLD " +
                "time=${System.currentTimeMillis() - t0}ms")

        return score >= NCC_THRESHOLD
    }

    // -----------------------------------------------------------------------
    // Remaining methods unchanged from original
    // -----------------------------------------------------------------------

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

        var totalR = 0L;  var totalG = 0L;  var totalB = 0L
        for (pixel in pixels) {
            totalR += (pixel shr 16) and 0xFF
            totalG += (pixel shr 8)  and 0xFF
            totalB +=  pixel         and 0xFF
        }

        val count  = pixels.size
        val meanR  = totalR.toDouble() / count
        val meanG  = totalG.toDouble() / count
        val meanB  = totalB.toDouble() / count

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

        var totalR = 0L;  var totalG = 0L;  var totalB = 0L
        for (pixel in pixels) {
            totalR += (pixel shr 16) and 0xFF
            totalG += (pixel shr 8)  and 0xFF
            totalB +=  pixel         and 0xFF
        }

        val count  = pixels.size
        val meanR  = totalR.toDouble() / count
        val meanG  = totalG.toDouble() / count
        val meanB  = totalB.toDouble() / count

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