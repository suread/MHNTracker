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
import org.junit.Assert.assertFalse
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

    @Test
    fun `findShift returns immediately if first shift check is a confident match`() {
        val scriptedResults = listOf(
            MeasuredShift(offset = 40, confidence = 0.95),
            MeasuredShift(offset = 42, confidence = 0.95),
        )
        var callIndex = 0
        val fakeMeasure: ShiftMeasurer = { _, _, _, _, _, _, _ -> scriptedResults[callIndex++]}

        val frame = makeFrame(10,200, listOf<FrameMarker>())

        val search = composer.findShift(frame, frame, x1 = 2, x2 = 4, yTopStart = 180, height = 5, maxShift = 500, step = 5, bottomMargin = 5, confidenceThreshold = 0.9, measure = fakeMeasure)

        assertEquals(40, search.offset)
        assertTrue(search.accepted)
        assertEquals(1, callIndex)

    }

    @Test
    fun `findShift retries exactly once when first match is low confidence and 2nd is above threshold`() {
        val scriptedResults = listOf(
            MeasuredShift(offset = 40, confidence = 0.5),
            MeasuredShift(offset = 42, confidence = 0.95),
            MeasuredShift(offset = 43, confidence = 0.95),
        )
        var callIndex = 0
        val fakeMeasure: ShiftMeasurer = { _, _, _, _, _, _, _ -> scriptedResults[callIndex++]}

        val frame = makeFrame(10,200, listOf<FrameMarker>())

        val search = composer.findShift(frame, frame, x1 = 2, x2 = 4, yTopStart = 180, height = 5, maxShift = 500, step = 5, bottomMargin = 5, confidenceThreshold = 0.9, measure = fakeMeasure)

        assertEquals(42, search.offset)
        assertTrue(search.accepted)
        assertEquals(2, callIndex)

    }

    @Test
    fun `findShift returns shift = 0 and accepted = false if no matches are above the confidence threshold`() {
        val scriptedResults = listOf(
            MeasuredShift(offset = 40, confidence = 0.5),
            MeasuredShift(offset = 42, confidence = 0.5),
            MeasuredShift(offset = 40, confidence = 0.5),
            MeasuredShift(offset = 42, confidence = 0.5),
        )
        var callIndex = 0
        val fakeMeasure: ShiftMeasurer = { _, _, _, _, _, _, _ -> scriptedResults[callIndex++]}

        val frame = makeFrame(10,200, listOf<FrameMarker>())

        val search = composer.findShift(frame, frame, x1 = 2, x2 = 4, yTopStart = 180, height = 5, maxShift = 500, step = 5, bottomMargin = 5, confidenceThreshold = 0.9, measure = fakeMeasure)

        assertEquals(0, search.offset)
        assertFalse(search.accepted)
    }

    @Test
    fun `findShift uses all the expected template area when matches continue to that point`() {
        val scriptedResults = listOf(
            MeasuredShift(offset = 40, confidence = 0.5),
            MeasuredShift(offset = 42, confidence = 0.5),
            MeasuredShift(offset = 40, confidence = 0.5),
            MeasuredShift(offset = 42, confidence = 0.5),
        )
        var callIndex = 0
        val fakeMeasure: ShiftMeasurer = { _, _, _, _, _, _, _ -> scriptedResults[callIndex++]}

        val frame = makeFrame(10,200, listOf<FrameMarker>())

        val search = composer.findShift(frame, frame, x1 = 2, x2 = 4, yTopStart = 180, height = 5, maxShift = 500, step = 5, bottomMargin = 5, confidenceThreshold = 0.9, measure = fakeMeasure)

        // for frame made and values given, expecting findShift to look for values of yTop = 180, 185,190. At 190, the template area will run from y=190 to y=195 - callIndex should be 3
        assertEquals(0, search.offset)
        assertFalse(search.accepted)
        assertEquals(3, callIndex)
    }

    @Test
    fun `findShift does not go beyond the expected template range when no match is found`() {
        val scriptedResults = listOf(
            MeasuredShift(offset = 40, confidence = 0.5),
            MeasuredShift(offset = 42, confidence = 0.5),
            MeasuredShift(offset = 40, confidence = 0.5),
            MeasuredShift(offset = 42, confidence = 0.5),
        )
        var callIndex = 0
        val fakeMeasure: ShiftMeasurer = { _, _, _, _, _, _, _ -> scriptedResults[callIndex++]}

        val frame = makeFrame(10,200, listOf<FrameMarker>())

        val search = composer.findShift(frame, frame, x1 = 2, x2 = 4, yTopStart = 180, height = 5, maxShift = 500, step = 5, bottomMargin = 6, confidenceThreshold = 0.9, measure = fakeMeasure)

        // for frame made and values given, expecting findShift to look for values of yTop = 180, 185.
        // At 190, the template area will run from y=190 to y=195, but with bottomMargin = 6 this should now cross over into the margin area - callIndex should be 2
        assertEquals(0, search.offset)
        assertFalse(search.accepted)
        assertEquals(2, callIndex)

    }

}
