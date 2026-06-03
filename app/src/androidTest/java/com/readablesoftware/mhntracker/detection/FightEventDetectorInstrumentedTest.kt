package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FightEventDetectorInstrumentedTest {

    // Replace with the actual video folder name once the recording is in place.
    private val fightBreakRecording = "screen-20250918-161107-tzitzi-fight"

    private lateinit var detector: FightEventDetector

    @Before
    fun setUp() {
        detector = FightEventDetector()  // uses MlKitTextDetector by default
    }

    @Test
    fun break_is_detected_from_fully_visible_frame_137() {
        // Primary positive test with real ML Kit. Must always pass.
        val frame = loadTestFrame(fightBreakRecording, frameIndex = 137)
        assertTrue(runBlocking { detector.isBreakVisible(frame) })
    }

    @Test
    fun break_is_not_detected_from_fight_frame_before_break_134() {
        // Primary negative test with real ML Kit. Must always pass.
        val frame = loadTestFrame(fightBreakRecording, frameIndex = 134)
        assertFalse(runBlocking { detector.isBreakVisible(frame) })
    }

    @Test
    fun break_partially_visible_in_animation_frame_135_is_informational() {
        // Frame 135: BREAK beginning to appear. Detection acceptable but not required.
        val frame = loadTestFrame(fightBreakRecording, frameIndex = 135)
        assumeTrue(
            "Frame 135 partial BREAK not detected — acceptable during animation",
            runBlocking { detector.isBreakVisible(frame) }
        )
    }

    private fun loadTestFrame(videoName: String, frameIndex: Int): Bitmap {
        val path = "frames/$videoName/frame_${frameIndex.toString().padStart(4, '0')}.png"
        val context = InstrumentationRegistry.getInstrumentation().context
        return context.assets.open(path).use { stream ->
            BitmapFactory.decodeStream(stream)
                ?: error("Failed to decode bitmap from: $path")
        }
    }
}
