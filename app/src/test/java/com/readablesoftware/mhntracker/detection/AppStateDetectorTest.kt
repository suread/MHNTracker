package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

private fun framesFrom(directory: String): List<String> {
    val dir = File("src/test/resources/frames/$directory")
    print("src/test/resources/frames/$directory")
    print(dir.listFiles())
    return dir.listFiles { f -> f.extension == "png" }
        ?.map { it.name }
        ?: emptyList()
}

private fun loadTestFrame(directoryName: String, frameName: String): Bitmap {
    val path   = "frames/$directoryName/$frameName"
    val stream = Thread.currentThread().contextClassLoader!!.getResourceAsStream(path)
        ?: error("Test resource not found: $path")
    return BitmapFactory.decodeStream(stream)
        ?: error("Failed to decode bitmap from: $path")
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









