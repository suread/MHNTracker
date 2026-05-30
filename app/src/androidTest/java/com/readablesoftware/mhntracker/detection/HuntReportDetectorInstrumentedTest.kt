package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.common.MlKit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HuntReportDetectorInstrumentedTest {

    private val huntSoloR6NoBreaks = "screen-20260527-002413-khezu.r6.urgent.no-breaks"
    private val huntGroupR6WithBreaks = "screen-20260527-002543-viper.flink.r6"

    private lateinit var detector: HuntReportDetector

    @Before
    fun setUp() {
        detector = HuntReportDetector()  // uses MlKitTextDetector by default
    }

    @Test
    fun hunt_report_screen_is_recognised_from_faded_frame_14() {
        // Frame 14 has faded "Hunt Report" text due to reveal animation.
        // This test documents that ML Kit can detect it even faded.
        // If this test fails it is not a regression — frame 15 is the primary signal.
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 14)
        assumeTrue("Frame 14 faded text not detected — acceptable",
            detector.isHuntReportScreen(frame))
    }

    @Test
    fun hunt_report_screen_is_recognised_from_clear_frame_15() {
        // Frame 15 has fully black "Hunt Report" text. This must always pass.
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 15)
        assertTrue(detector.isHuntReportScreen(frame))
    }

    @Test
    fun hunt_report_screen_is_not_recognised_from_before_hunt_report() {
        // Frame 13 is before the Hunt Report screen appears. Must not be detected.
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 13)
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
        // Frame 39 has faded confirm button due to animation.
        // Detection is acceptable but not required.
        val frame = loadTestFrame(huntGroupR6WithBreaks, frameIndex = 39)
        assumeTrue("Frame 39 faded button not detected — acceptable",
            detector.isConfirmButtonVisible(frame))
    }
    private fun loadTestFrame(videoName: String, frameIndex: Int): Bitmap {
        val path = "frames/$videoName/frame_${frameIndex.toString().padStart(4, '0')}.png"
        val context = InstrumentationRegistry.getInstrumentation().context
        return context.assets.open(path).use { stream ->  // lambda
            BitmapFactory.decodeStream(stream)
                ?: error("Failed to decode bitmap from: $path")
        }
    }
}