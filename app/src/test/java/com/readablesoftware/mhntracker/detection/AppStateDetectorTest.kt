package com.readablesoftware.mhntracker.detection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import com.readablesoftware.mhntracker.testutil.TestFrameLoader.loadTestFrame
import org.junit.Ignore

private fun framesFrom(directory: String): List<String> {
    val dir = File("src/test/resources/frames/$directory")
    return dir.listFiles { f -> f.extension == "png" }
        ?.map { it.name }
        ?: emptyList()
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36])
class MapDetectedTest(private val frameName: String) {

    companion object {
        private const val DIRECTORY = "map_detection/positive"

        @JvmStatic
        @Parameters(name = "{0}")
        fun frames() = framesFrom(DIRECTORY)
    }

    private lateinit var detector: AppStateDetector

    @Before
    fun setUp() {
        detector = AppStateDetector()
        ShadowLog.stream = System.out
    }

    @Test
    fun `map screen is detected`() {
        val frame = loadTestFrame(DIRECTORY, frameName)
        assertTrue(
            "Frame should be recognised as a map frame",
            detector.isMapScreen(frame)
        )
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36])
class MapNotDetectedTest(private val frameName: String) {

    companion object {
        private const val DIRECTORY = "map_detection/negative"

        @JvmStatic
        @Parameters(name = "{0}")
        fun frames() = framesFrom(DIRECTORY)
    }

    private lateinit var detector: AppStateDetector

    @Before
    fun setUp() {
        detector = AppStateDetector()
    }

    @Test
    fun `map screen is not detected`() {
        val frame = loadTestFrame(DIRECTORY,frameName)
        assertFalse(
            "Frame should not be recognised as a map frame",
            detector.isMapScreen(frame)
        )
    }


}

@Ignore("Do not run routinely")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LargeVolumeNegativeMapTests {
    private lateinit var detector: AppStateDetector

    @Before
    fun setUp() {
        detector = AppStateDetector()
    }

    @Test
    fun `brute force scan negative frames`() {
        val topLevelDirectory = "map_detection/negative/_No_surplus"   // one level up from actual subfolders
        var frameCount = 0
        for ((subdirName, framePath) in framePathsIn(topLevelDirectory)) {
            val frame = loadTestFrame(topLevelDirectory, framePath)
            frameCount++
            if (detector.isMapScreen(frame)) {
                println("FALSE POSITIVE: $topLevelDirectory/$framePath")
            }
        }
        println(frameCount)
    }

    @Test
    fun `brute force scan negative frames 2`() {
        val topLevelDirectory = "map_detection/negative/_Extra_frames"   // one level up from actual subfolders
        var frameCount = 0
        for ((subdirName, framePath) in framePathsIn(topLevelDirectory)) {
            val frame = loadTestFrame(topLevelDirectory, framePath)
            frameCount++
            if (detector.isMapScreen(frame)) {
                println("FALSE POSITIVE: $topLevelDirectory/$framePath")
            }
        }
        println(frameCount)
    }

    private fun framePathsIn(topLevelDirectory: String): List<Pair<String, String>> {
        val baseDir = File("src/test/resources/frames/$topLevelDirectory")
        val subdirs = baseDir.listFiles { f -> f.isDirectory } ?: emptyArray()

        return subdirs.flatMap { subdir ->
            val pngs = subdir.listFiles { f -> f.extension == "png" } ?: emptyArray()
            pngs.map { png -> subdir.name to "${subdir.name}/${png.name}" }
        }
    }
}

class MapDetectionTest {

    @Test
    fun `positive frames directory is not empty`() {
        val frames = framesFrom("map_detection/positive")
        assertTrue("No positive test frames found", frames.isNotEmpty())
    }

    @Test
    fun `negative frames directory is not empty`() {
        val frames = framesFrom("map_detection/negative")
        assertTrue("No negative test frames found", frames.isNotEmpty())
    }

}









