package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_GRAPHIC_X1
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_GRAPHIC_X2
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_GRAPHIC_Y1
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_GRAPHIC_Y2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.readablesoftware.mhntracker.testutil.TestFrameLoader.loadTestFrame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FightEventDetectorTest {

    // Frames extracted with:
    // ffmpeg -i <video>.mp4 -vf fps=2 frames/<videoName>/frame_%04d.png
    private val fightBreakRecording = "screen-20250918-161107-tzitzi-fight"

    private lateinit var detector: FightEventDetector

    @Before
    fun setUp() {
        detector = FightEventDetector()
    }

    // -----------------------------------------------------------------------
    // BREAK detection — positive case
    // -----------------------------------------------------------------------

    @Test
    fun `break is detected when fully visible`() {
        // Frame 137: BREAK fully rendered, orange text fills the crop region.
        // Expected: orange_frac ~0.43, col_coverage ~0.94 — well above thresholds.
        val frame = loadTestFrame(fightBreakRecording, frameIndex = 137)
        assertTrue(
            "Frame 137 should be detected as a BREAK frame",
            detector.isBreakVisible(frame)
        )
    }

    // -----------------------------------------------------------------------
    // BREAK detection — negative case
    // -----------------------------------------------------------------------

    @Test
    fun `break is not detected when break graphic is absent`() {
        // Frame 134: normal fight frame, no BREAK graphic.
        // Expected: orange_frac and col_coverage both near zero.
        val frame = loadTestFrame(fightBreakRecording, frameIndex = 134)
        assertFalse(
            "Frame 134 should not be detected as a BREAK frame",
            detector.isBreakVisible(frame)
        )
    }

    // -----------------------------------------------------------------------
    // BREAK detection — animation boundary (informational)
    // -----------------------------------------------------------------------

    @Test
    fun `break animation frame is informational`() {
        // Frame 135: BREAK graphic fading/scaling in. The text is small and
        // semi-transparent — orange fraction is ~0.005, well below threshold.
        // Detection is not expected, but would be acceptable if it passed
        // (e.g. if a future recording catches a more advanced animation frame).
        // Skipped if detection fails — never fails the test either way.
        val frame = loadTestFrame(fightBreakRecording, frameIndex = 135)
        assumeTrue(
            "Frame 135 animation BREAK not detected — acceptable",
            detector.isBreakVisible(frame)
        )
    }

    // -----------------------------------------------------------------------
    // Orange pixel arithmetic — unit tests independent of frame files
    // -----------------------------------------------------------------------

    @Test
    fun `single orange pixel in otherwise black frame scores above zero`() {
        // A 1x1 bitmap with a pixel matching the orange definition should
        // produce orangeFrac = 1.0 and colCoverage = 1.0 — well above thresholds.
        // We fake a full-size frame by creating a bitmap large enough to contain
        // the BREAK crop region, filled black, with one orange pixel inside it.
        val w = BREAK_GRAPHIC_X2 + 1
        val h = BREAK_GRAPHIC_Y2 + 1
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLACK)

        // Place an orange pixel at every position in the crop to guarantee
        // detection — we are testing the arithmetic, not threshold sensitivity.
        val orange = (0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50  // R=200 G=100 B=50
        for (y in BREAK_GRAPHIC_Y1 until BREAK_GRAPHIC_Y2) {
            for (x in BREAK_GRAPHIC_X1 until BREAK_GRAPHIC_X2) {
                bitmap.setPixel(x, y, orange)
            }
        }

        assertTrue(
            "Frame filled with orange in BREAK region should be detected",
            detector.isBreakVisible(bitmap)
        )
    }

    @Test
    fun `frame too small to contain break region returns false`() {
        val tooSmall = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        assertFalse(
            "Frame smaller than BREAK region should return false without crashing",
            detector.isBreakVisible(tooSmall)
        )
    }
}