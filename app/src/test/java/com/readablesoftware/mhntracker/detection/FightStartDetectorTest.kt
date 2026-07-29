package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import androidx.test.core.app.ApplicationProvider
import com.readablesoftware.mhntracker.model.HuntResult
import com.readablesoftware.mhntracker.model.MaterialDrop
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.readablesoftware.mhntracker.testutil.TestFrameLoader.loadTestFrame
import com.readablesoftware.mhntracker.testutil.TestVideoLoader.loadTestVideo
import org.junit.Ignore
import com.readablesoftware.mhntracker.detection.FightStartDetector

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FightStartDetectorTest {

    private lateinit var detector: FightStartDetector

    // Paths to the production template assets, loaded directly from src/main/assets/
    // so tests use the same file as production — no duplicate asset needed.
    // This path is relative to the project root, which is the working directory
    // when Robolectric tests run via Gradle.
    private val templatePaths = listOf(
        "src/main/assets/lets_hunt_template.png",
        "src/main/assets/start_hunting_template.png"
    )

    @Before
    fun setUp() {
        // Load the production template via the file path constructor.
        // All tests share the same detector instance — the template is fixed.
        detector = FightStartDetector.createFromFile(templatePaths)
    }

    @Test
    fun `fight start screen is recognised from frame with Lets Hunt text visible`() {
        val frame = loadTestFrame("fight-start-detection/positive", frameIndex = 0)
        assertTrue(
            "Radobaan Frame 0 should be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun `fight start screen is recognised from frame with Start Hunting text visible`() {
        val frame = loadTestFrame("fight-start-detection/positive", frameIndex = 1)
        assertTrue(
            "Rajang frame 1 should be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun `fight start screen is recognised from frame 11 with Start Hunting text visible`() {
        val frame = loadTestFrame("fight-start-detection/positive", frameIndex = 11)
        assertTrue(
            "Frame 11 should be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun `fight start screen is recognised from frame 12 with Lets Hunt text visible`() {
        val frame = loadTestFrame("fight-start-detection/positive", frameIndex = 12)
        assertTrue(
            "Riftborne Zinogre frame 12 should be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun `fight start screen is recognised from frame 18 with Lets Hunt text visible in group hunt`() {
        val frame = loadTestFrame("fight-start-detection/positive", frameIndex = 18)
        assertTrue(
            "Viper Tobi-Kadachi group hunt frame 18 should be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun `fight start screen is recognised from frame 19 with Start Hunting text visible`() {
        val frame = loadTestFrame("fight-start-detection/positive", frameIndex = 19)
        assertTrue(
            "Frame 19 should be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun `map screen is not recognised as fight start`() {
        val frame = loadTestFrame("fight-start-detection/negative", frameIndex = 51)
        assertFalse(
            "Map frame 51 should not be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun `hunt report screen is not recognised as fight start`() {
        val frame = loadTestFrame("fight-start-detection/negative", frameIndex = 15)
        assertFalse(
            "Hunt report frame 15 should not be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun `waiting for hunters lobby screen is not recognised as fight start`() {
        val frame = loadTestFrame("fight-start-detection/negative", frameIndex = 3)
        assertFalse(
            "Lobby frame should not be recognised as hunt start screen",
            detector.isFightStartVisible(frame)
        )
    }

    @Test
    fun `frame too small to contain hunt starting text returns false`() {
        val tooSmall = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        assertFalse(
            "Frame smaller than hunt starting text should return false without crashing",
            detector.isFightStartVisible(tooSmall)
        )
    }


}