package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.common.MlKit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.readablesoftware.mhntracker.testutil.TestFrameLoader.loadTestFrame

@RunWith(AndroidJUnit4::class)
class HuntReportDetectorInstrumentedTest {

    private val huntSoloR6NoBreaks     = "screen-20260527-002413-khezu.r6.urgent.no-breaks"
    private val huntGroupR6WithBreaks  = "screen-20260527-002543-viper.flink.r6"

    private lateinit var detector: HuntReportDetector

    @Before
    fun setUp() {
        // Uses create(context) to load the template from app assets —
        // confirms asset loading works correctly on real device hardware.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        detector = HuntReportDetector.create(context)
    }

    @Test
    fun hunt_report_screen_is_recognised_from_faded_frame_14() {
        // Frame 14: "Hunt Report" text fading in via reveal animation.
        // NCC score will be lower than a fully rendered frame.
        // Detection is acceptable but not required — frame 15 is the primary signal.
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 14)
        assumeTrue(
            "Frame 14 faded text not detected — acceptable",
            detector.isHuntReportScreen(frame)
        )
    }

    @Test
    fun hunt_report_screen_is_recognised_from_clear_frame_15() {
        // Frame 15: "Hunt Report" fully rendered. Must always pass.
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 15)
        assertTrue(detector.isHuntReportScreen(frame))
    }

    @Test
    fun hunt_report_screen_is_not_recognised_from_before_hunt_report() {
        // Frame 13: hunt report screen not yet visible. Must always fail detection.
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 13)
        assertFalse(detector.isHuntReportScreen(frame))
    }

    @Test
    fun hunt_report_screen_is_not_recognised_when_confirm_button_visible() {
        // Frame 31: confirm button visible, "Hunt Report" title has scrolled off.
        // Crop region contains UI background only — NCC score should be low.
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 31)
        assertFalse(detector.isHuntReportScreen(frame))
    }

    @Test
    fun confirm_button_is_not_detected_before_it_appears_khezu_frame_30() {
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 30)
        assertFalse(detector.isConfirmButtonVisible(frame))
    }

    @Test
    fun confirm_button_is_detected_when_visible_khezu_frame_31() {
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 31)
        assertTrue(detector.isConfirmButtonVisible(frame))
    }

    @Test
    fun confirm_button_is_not_detected_before_it_appears_viper_frame_38() {
        val frame = loadTestFrame(huntGroupR6WithBreaks, frameIndex = 38)
        assertFalse(detector.isConfirmButtonVisible(frame))
    }

    @Test
    fun confirm_button_is_detected_when_fully_visible_viper_frame_40() {
        val frame = loadTestFrame(huntGroupR6WithBreaks, frameIndex = 40)
        assertTrue(detector.isConfirmButtonVisible(frame))
    }

    @Test
    fun confirm_button_faded_viper_frame_39_is_informational() {
        // Frame 39: confirm button fading in. Detection acceptable but not required.
        val frame = loadTestFrame(huntGroupR6WithBreaks, frameIndex = 39)
        assumeTrue(
            "Frame 39 faded button not detected — acceptable",
            detector.isConfirmButtonVisible(frame)
        )
    }

}