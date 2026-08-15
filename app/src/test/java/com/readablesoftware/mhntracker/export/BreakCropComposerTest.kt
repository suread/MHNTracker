package com.readablesoftware.mhntracker.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.readablesoftware.mhntracker.detection.FightScreenConstants
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_TEXT_X1
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_TEXT_X2
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_TEXT_Y1
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_TEXT_Y2
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
class BreakCropComposerTest {

    private lateinit var composer: BreakCropComposer
    private lateinit var tempSessionDir: File

    @Before
    fun setUp() {
        composer = BreakCropComposer()
        tempSessionDir = createTempDirectory("break-crop-composer-test-").toFile()
    }

    @After
    fun tearDown() {
        tempSessionDir.deleteRecursively()
    }

    private fun cropWidth() = FightScreenConstants.BREAK_TEXT_X2 - FightScreenConstants.BREAK_TEXT_X1
    private fun cropHeight() = FightScreenConstants.BREAK_TEXT_Y2 - FightScreenConstants.BREAK_TEXT_Y1

    // Frame filled with outsideColour, with a small marker square placed exactly
    // at the crop region's top-left and bottom right source coordinates. If addBreakFrame crops
    // from the wrong offset or uses the wrong constants, the markers will not
    // appear at the expected position in the composite output.
    private fun frameWithMarkers(frameColour: Int = Color.MAGENTA, topLeftColour: Int = Color.RED, bottomRightColour: Int = Color.BLUE): Bitmap {
        val frame = createBitmap(1000, 1000)
        Canvas(frame).apply {
            drawColor(frameColour)
            val topLeftPaint = Paint().apply { color = topLeftColour }
            drawRect(
                FightScreenConstants.BREAK_TEXT_X1.toFloat(),
                FightScreenConstants.BREAK_TEXT_Y1.toFloat(),
                (FightScreenConstants.BREAK_TEXT_X1 + 5).toFloat(),
                (FightScreenConstants.BREAK_TEXT_Y1 + 5).toFloat(),
                topLeftPaint
            )
            // Bottom-right marker — placed just inside the crop's far corner
            // (X2/Y2 are exclusive bounds, so the marker sits at X2-5..X2-1 etc.)
            val bottomRightPaint = Paint().apply { color = Color.BLUE }
            drawRect(
                (FightScreenConstants.BREAK_TEXT_X2 - 5).toFloat(),
                (FightScreenConstants.BREAK_TEXT_Y2 - 5).toFloat(),
                FightScreenConstants.BREAK_TEXT_X2.toFloat(),
                FightScreenConstants.BREAK_TEXT_Y2.toFloat(),
                bottomRightPaint
            )
        }
        return frame
    }

    @Test
    fun `addBreakFrame extracts the crop from the correct source coordinates`() {
        composer.addBreakFrame(frameWithMarkers(Color.MAGENTA, Color.RED, Color.BLUE))
        val outputFile = composer.exportComposite(tempSessionDir)
        val composite = BitmapFactory.decodeFile(outputFile.path)

        assertEquals(
            "marker at source crop's top-left should appear at row 0's content top-left",
            Color.RED,
            composite.getPixel(2, BreakCropComposer.BORDER_TOP + 2)
        )
        assertEquals(
            "marker at source crop's bottom-right should appear at row 0's content bottom-right, " +
                    "proving crop width and height (not just origin) are correct",
            Color.BLUE,
            composite.getPixel(
                (FightScreenConstants.BREAK_TEXT_X2 - FightScreenConstants.BREAK_TEXT_X1) - 2,
                BreakCropComposer.BORDER_TOP + (FightScreenConstants.BREAK_TEXT_Y2 - FightScreenConstants.BREAK_TEXT_Y1) - 2
            )
        )
    }

    @Test
    fun `crop width matches the configured break text region`() {
        composer.addBreakFrame(frameWithMarkers(Color.RED))

        val outputFile = composer.exportComposite(tempSessionDir)
        val composite = BitmapFactory.decodeFile(outputFile.path)

        assertEquals(cropWidth(), composite.width)
    }

    @Test
    fun `multiple addBreakFrame calls accumulate crops in call order`() {
        val coloursInOrder = listOf(Color.RED, Color.GREEN, Color.BLUE)
        coloursInOrder.forEach { colour ->
            val frame = createBitmap(1000, 1000)
            Canvas(frame).drawColor(colour)
            composer.addBreakFrame(frame)
        }

        val outputFile = composer.exportComposite(tempSessionDir)
        val composite = BitmapFactory.decodeFile(outputFile.path)

        val unitHeight = cropHeight() + BreakCropComposer.BORDER_TOP + BreakCropComposer.BORDER_BOTTOM
        coloursInOrder.forEachIndexed { index, expectedColour ->
            val y = unitHeight * index + BreakCropComposer.BORDER_TOP + 5
            assertEquals(
                "row $index should hold the crop added $index-th, in call order",
                expectedColour,
                composite.getPixel(5, y)
            )
        }
    }

    @Ignore(
        "capturedAt cannot be verified black-box: the timestamp only ever " +
                "manifests as rendered text under each crop in the composite image, " +
                "and Robolectric cannot reliably assert exact text-rendering pixel " +
                "content (font rasterisation varies by environment — see project " +
                "notes on Canvas/text testing limitations). Verifying capturedAt would " +
                "require exposing internal state for inspection, which reopens the " +
                "visibility questions this class was designed to avoid. Accepted gap: " +
                "capturedAt's default (System.currentTimeMillis()) is trusted, not " +
                "verified, and any future custom-capturedAt behaviour is untested."
    )
    @Test
    fun `addBreakFrame stores the provided capturedAt time`() {
        TODO("See @Ignore reason above — no black-box way to verify this currently")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addBreakFrame throws when frame is smaller than the break text crop region`() {
        // One pixel short in both dimensions — Bitmap.createBitmap(frame, x, y, w, h)
        // should throw rather than silently produce a corrupted/truncated crop.
        val tooSmallWidth  = BREAK_TEXT_X2 - 1
        val tooSmallHeight = BREAK_TEXT_Y2 - 1
        val frame = createBitmap(tooSmallWidth, tooSmallHeight)

        composer.addBreakFrame(frame)
    }

    @Test
    fun `export with zero crops produces minimal bordered bitmap, not a crash`() {

        val outputFile = composer.exportComposite(tempSessionDir)
        val bitmap = BitmapFactory.decodeFile(outputFile.path)

        assertEquals(BREAK_TEXT_X2 - BREAK_TEXT_X1, bitmap.width)
        assertEquals(20, bitmap.height)
        assertEquals(Color.BLACK, bitmap.getPixel(13, 13))
    }

    @Test
    fun `export produces a bitmap with filename matching time it was generated`() {
        val outputFile = composer.exportComposite(tempSessionDir, 1786549772230)

        val outputFileName = outputFile.name

        // output filename expected to have format "break_crops-{timestamp}.png where timestamp has format "yyyyMMdd-HHmmss-SSS", Locale.UK
        // since this is expected to be debug only, using UK rather than user timezone is fine
        // any change - accidental or deliberate - and this test will break!
        val storeMillis = ExportTimestamps.parse(outputFileName.substring(12, outputFileName.length - 4))

        assertEquals(1786549772230, storeMillis)
    }

    @Test
    fun `export produces a filename matching the intended content`() {
        val outputFile = composer.exportComposite(tempSessionDir)

        assertEquals("break_crops-", outputFile.name.substring(0, 12))
    }

    @Test
    fun `calling export twice results in 2 distinctly named files`() {

        val file1 = composer.exportComposite(tempSessionDir, 1786549772230)
        val file2 = composer.exportComposite(tempSessionDir, 1786549773230)

        assertTrue(file1.name != file2.name)
        assertEquals(2, tempSessionDir.listFiles()!!.size)
    }

    @Test
    fun `export produces a bitmap of the right dimensions for 1 crop`() {

        composer.addBreakFrame(frameWithMarkers(Color.GREEN))
        val outputFile = composer.exportComposite(tempSessionDir)

        val bitmap = BitmapFactory.decodeFile(outputFile.path)

        val expectedWidth = BREAK_TEXT_X2 - BREAK_TEXT_X1
        val expectedHeight = BREAK_TEXT_Y2 - BREAK_TEXT_Y1 + 20 + 40

        assertEquals(expectedWidth, bitmap.width)
        assertEquals(expectedHeight, bitmap.height)
        assertEquals(Color.BLACK, bitmap.getPixel(13, 13))
        assertEquals(Color.GREEN, bitmap.getPixel(13, 50))
    }

    @Test
    fun `export produces a bitmap of the right dimensions for 5 crops`() {

        val colours = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN)
        colours.forEach { colour ->
            val bmp = createBitmap(1000, 1000)
            Canvas(bmp).drawColor(colour)
            composer.addBreakFrame(bmp)
        }

        val outputFile = composer.exportComposite(tempSessionDir)
        val bitmap = BitmapFactory.decodeFile(outputFile.path)

        val expectedWidth = BREAK_TEXT_X2 - BREAK_TEXT_X1
        val unitHeight = BREAK_TEXT_Y2 - BREAK_TEXT_Y1 + BreakCropComposer.BORDER_TOP + BreakCropComposer.BORDER_BOTTOM
        val expectedHeight = unitHeight * 5

        assertEquals(expectedWidth, bitmap.width)
        assertEquals(expectedHeight, bitmap.height)
        assertEquals(Color.BLACK, bitmap.getPixel(13, 13))
        assertEquals(Color.RED, bitmap.getPixel(13, 50))

        colours.forEachIndexed { i, expectedColour ->
            val y =
                unitHeight * i + BreakCropComposer.BORDER_TOP + 30   // some offset safely inside content region
            assertEquals("row $i content colour", expectedColour, bitmap.getPixel(13, y))
        }
    }

}






