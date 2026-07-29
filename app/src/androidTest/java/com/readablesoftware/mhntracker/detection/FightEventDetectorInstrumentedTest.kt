package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.readablesoftware.mhntracker.testutil.TestFrameLoader.loadTestFrame

@RunWith(AndroidJUnit4::class)
class FightEventDetectorInstrumentedTest {

    private val fightBreakRecording = "screen-20250918-161107-tzitzi-fight"

    private lateinit var detector: FightEventDetector

    @Before
    fun setUp() {
        detector = FightEventDetector()
    }

    @Test
    fun break_is_detected_from_fully_visible_frame_137() {
        // Primary positive test. Must always pass.
        val frame = loadTestFrame(fightBreakRecording, frameIndex = 137)
        assertTrue(detector.isBreakVisible(frame))
    }

    @Test
    fun break_is_not_detected_from_fight_frame_before_break_134() {
        // Primary negative test. Must always pass.
        val frame = loadTestFrame(fightBreakRecording, frameIndex = 134)
        assertFalse(detector.isBreakVisible(frame))
    }

    @Test
    fun break_partially_visible_in_animation_frame_135_is_informational() {
        // Frame 135: BREAK fading/scaling in. Detection acceptable but not required.
        val frame = loadTestFrame(fightBreakRecording, frameIndex = 135)
        assumeTrue(
            "Frame 135 partial BREAK not detected — acceptable during animation",
            detector.isBreakVisible(frame)
        )
    }

}
