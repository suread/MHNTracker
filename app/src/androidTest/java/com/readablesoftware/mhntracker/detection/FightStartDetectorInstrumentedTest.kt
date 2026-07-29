package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.common.MlKit
import com.readablesoftware.mhntracker.testutil.TestFrameLoader.loadTestFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.intArrayOf

@RunWith(AndroidJUnit4::class)
class FightStartDetectorInstrumentedTest {

    private lateinit var detector: FightStartDetector

    @Before
    fun setUp() {
        // Uses create(context) to load the template from app assets —
        // confirms asset loading works correctly on real device hardware.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        detector = FightStartDetector.create(context)
    }

    @Test
    fun fight_start_screen_is_recognised_from_frame_with_lets_hunt_text_visible() {
        val frame = loadTestFrame("fight-start-detection/positive", frameIndex = 0)
        assertTrue(
            "Radobaan Frame 0 should be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun fight_start_screen_is_recognised_from_frame_with_start_hunting_text_visible() {
        val frame = loadTestFrame("fight-start-detection/positive", frameIndex = 1)
        assertTrue(
            "Rajang frame 1 should be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun fight_start_screen_is_recognised_from_frame_11_with_start_hunting_text_visible() {
        val frame = loadTestFrame("fight-start-detection/positive", frameIndex = 11)
        assertTrue(
            "Frame 11 should be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun fight_start_screen_is_recognised_from_frame_12_with_lets_hunt_text_visible() {
        val frame = loadTestFrame("fight-start-detection/positive", frameIndex = 12)
        assertTrue(
            "Riftborne Zinogre frame 12 should be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun fight_start_screen_is_recognised_from_frame_18_with_lets_hunt_text_visible_in_group_hunt() {
        val frame = loadTestFrame("fight-start-detection/positive", frameIndex = 18)
        assertTrue(
            "Viper Tobi-Kadachi group hunt frame 18 should be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun fight_start_screen_is_recognised_from_frame_19_with_start_hunting_text_visible() {
        val frame = loadTestFrame("fight-start-detection/positive", frameIndex = 19)
        assertTrue(
            "Frame 19 should be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun map_screen_is_not_recognised_as_fight_start() {
        val frame = loadTestFrame("fight-start-detection/negative", frameIndex = 51)
        assertFalse(
            "Map frame 51 should not be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun hunt_report_screen_is_not_recognised_as_fight_start() {
        val frame = loadTestFrame("fight-start-detection/negative", frameIndex = 15)
        assertFalse(
            "Hunt report frame 15 should not be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun waiting_for_hunters_lobby_screen_is_not_recognised_as_fight_start() {
        val frame = loadTestFrame("fight-start-detection/negative", frameIndex = 3)
        assertFalse(
            "Lobby frame should not be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun frame_too_small_to_contain_hunt_starting_text_returns_false() {
        val tooSmall = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        assertFalse(
            "Frame smaller than hunt starting text should return false without crashing",
            detector.isFightStartVisible(tooSmall)
        )
    }

}
