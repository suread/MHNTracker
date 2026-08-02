package com.readablesoftware.mhntracker.export

import android.graphics.BitmapFactory
import android.graphics.Color
import com.readablesoftware.mhntracker.detection.RewardsScreenConstants.STATUS_AREA_HEIGHT
import com.readablesoftware.mhntracker.testutil.FrameMarker
import com.readablesoftware.mhntracker.testutil.TestFrameMaker
import com.readablesoftware.mhntracker.testutil.TestFrameMaker.makeFrame
import com.readablesoftware.mhntracker.util.ExportTimestamps
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HuntReportComposerTest {
    private lateinit var composer: HuntReportComposer
    private lateinit var tempSessionDir: File
    @Before
    fun setUp() {
        composer = HuntReportComposer()
        tempSessionDir = createTempDirectory("hunt-report-composer-test-").toFile()
    }

    @After
    fun tearDown() {
        tempSessionDir.deleteRecursively()
    }

   @Test
    fun `adding single frame gives composite size expected for frame`() {
       val frame = TestFrameMaker.pixel7(listOf<FrameMarker>())
       composer.addFrame(frame)

       val compositeFile = composer.exportComposite(tempSessionDir)
       val composite = BitmapFactory.decodeFile(compositeFile.path)

       // composite for single frame should have same width as frame, height = original height -140px (based on size of pixel status bar area
       assertEquals(frame.width, composite.width)
       assertEquals(frame.height - STATUS_AREA_HEIGHT, composite.height)

    }

    @Test
    fun `adding multiple frames results in composite with correct frame order and position`() {
        // pixel 7 screen dimensions
        val width = 1080
        val height = 2400

        val coloursInOrder = listOf(Color.RED, Color.CYAN, Color.BLUE)

        coloursInOrder.forEach { colour ->
            val frame = TestFrameMaker.pixel7(arrayListOf(
                FrameMarker(0, 0, width, STATUS_AREA_HEIGHT, Color.GREEN),
                FrameMarker(20, STATUS_AREA_HEIGHT + 50, 25, STATUS_AREA_HEIGHT + 55, colour)))
            composer.addFrame(frame)
        }

        val compositeFile = composer.exportComposite(tempSessionDir)
        val composite = BitmapFactory.decodeFile(compositeFile.path)

        // composite for single frame should have same width as frame, height = original height -140px (based on size of pixel status bar area
        assertEquals(width, composite.width)
        assertEquals((height - STATUS_AREA_HEIGHT) * coloursInOrder.size, composite.height)

        coloursInOrder.forEachIndexed { index, expectedColour ->
            val y = (height - STATUS_AREA_HEIGHT) * index + 50
            assertEquals(
                "row $index should hold the crop added $index-th, in call order",
                expectedColour,
                composite.getPixel(20, y)
            )
        }
    }

    @Test
    fun `export with zero frames produces a small image as output, not a crash`() {
        val outputFile = composer.exportComposite(tempSessionDir)
        val bitmap = BitmapFactory.decodeFile(outputFile.path)

        assertEquals(Color.BLACK, bitmap.getPixel(13, 13))

    }

    @Test
    fun `export with zero frames produces a filename matching the intended content`() {
        val outputFile = composer.exportComposite(tempSessionDir)
        val bitmap = BitmapFactory.decodeFile(outputFile.path)

        assertEquals("hunt_report-", outputFile.name.substring(0, 12))

    }

    @Test
    fun `export with content produces a filename matching the intended content`() {
        val frame = makeFrame(100, STATUS_AREA_HEIGHT + 1)
        composer.addFrame(frame)

        val outputFile = composer.exportComposite(tempSessionDir)
        val bitmap = BitmapFactory.decodeFile(outputFile.path)

        assertEquals("hunt_report-", outputFile.name.substring(0, 12))

    }

    @Test
    fun `export produces a bitmap with filename matching time it was generated`() {
        val beforeMillis = System.currentTimeMillis()
        val outputFile = composer.exportComposite(tempSessionDir)
        val afterMillis = System.currentTimeMillis()

        val outputFileName = outputFile.name

        // output filename expected to have format "break_crops-{timestamp}.png where timestamp has format "yyyyMMdd-HHmmss-SSS", Locale.UK
        // since this is expected to be debug only, using UK rather than user timezone is fine
        // any change - accidental or deliberate - and this test will break!
        val storeMillis = ExportTimestamps.parse(outputFileName.substring(12, outputFileName.length - 4))

        assertTrue(storeMillis in beforeMillis..afterMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addFrame throws when any additional frame has different width than first frame`() {
        val firstFrame = makeFrame(100, STATUS_AREA_HEIGHT + 1)
        val smallFrame = makeFrame(99, STATUS_AREA_HEIGHT + 1)

        composer.addFrame(firstFrame)
        composer.addFrame(smallFrame)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addFrame throws when frame is shorter than STATUS_AREA_HEIGHT`() {
        val frame = makeFrame(100, STATUS_AREA_HEIGHT - 1)

        composer.addFrame(frame)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addFrame throws when frame is equal to STATUS_AREA_HEIGHT`() {
        val frame = makeFrame(100, STATUS_AREA_HEIGHT)

        composer.addFrame(frame)
    }

}
