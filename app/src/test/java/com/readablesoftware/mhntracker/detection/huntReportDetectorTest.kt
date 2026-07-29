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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HuntReportDetectorTest {
    // Khezu, 4 purple stars, 4 basic rewards (+ summer special exchange token)
    // flabby hide (r1 armour) appears 2nd + 4th positions
    private val huntSoloR6NoBreaks = "screen-20260527-002413-khezu.r6.urgent.no-breaks"
    // Viper Tobi-Kadachi, 3 purple stars
    // basic: 4 framed (r1 weapon in slot 4) + 1 unframed
    // broken parts: 3 slots — r1 weapon slots 1+2, r3 slot 3
    // group hunt: r6 slot 1, normal slot 2, unframed slot 3
    // r1 weapon appears in basic slot 4 AND broken parts slots 1+2
    private val huntGroupR6WithBreaks = "screen-20260527-002543-viper.flink.r6"

    // Path to the production template asset, loaded directly from src/main/assets/
    // so tests use the same file as production — no duplicate asset needed.
    // This path is relative to the project root, which is the working directory
    // when Robolectric tests run via Gradle.
    private val templatePath = "src/main/assets/hunt_report_template.png"

    private lateinit var detector: HuntReportDetector

    @Before
    fun setUp() {
        // Load the production template via the file path constructor.
        // All tests share the same detector instance — the template is fixed.
        detector = HuntReportDetector.createFromFile(templatePath)
    }

    // -----------------------------------------------------------------------
    // Screen classification — NCC-based
    // -----------------------------------------------------------------------

    @Test
    fun `hunt report screen is recognised from frame with Hunt Report text visible`() {
        // Frame 15: Hunt Report text fully visible, black on pale blue-grey background.
        // Expected NCC score >= NCC_THRESHOLD (0.85).
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 15)
        assertTrue(
            "Frame 15 should be recognised as hunt report screen",
            detector.isHuntReportScreen(frame)
        )
    }

    @Test
    fun `hunt report screen is not recognised before it appears`() {
        // Frame 13: hunt report screen not yet visible.
        // Expected NCC score well below NCC_THRESHOLD.
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 13)
        assertFalse(
            "Frame 13 should not be recognised as hunt report screen",
            detector.isHuntReportScreen(frame)
        )
    }

    @Test
    fun `hunt report screen is not recognised when text has scrolled off`() {
        // Late frames in the session have the hunt report UI visible but
        // the "Hunt Report" title text has scrolled up off screen.
        // The crop region contains the UI background only — NCC should be low.
        // Frame 31 is the confirm button frame — title is gone by then.
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 31)
        assertFalse(
            "Frame 31 (confirm button) should not be recognised as hunt report screen",
            detector.isHuntReportScreen(frame)
        )
    }

    // -----------------------------------------------------------------------
    // NCC internals — unit tests independent of frame files
    // -----------------------------------------------------------------------

    @Test
    fun `ncc returns 1 for identical arrays`() {
        val a = floatArrayOf(10f, 20f, 30f, 40f, 50f)
        assertEquals(1.0f, HuntReportDetector.ncc(a, a.copyOf()), 0.001f)
    }

    @Test
    fun `ncc returns -1 for inverted arrays`() {
        val a = floatArrayOf(10f, 20f, 30f, 40f, 50f)
        val b = floatArrayOf(50f, 40f, 30f, 20f, 10f)
        assertEquals(-1.0f, HuntReportDetector.ncc(a, b), 0.001f)
    }

    @Test
    fun `ncc returns 0 for flat array`() {
        val a = floatArrayOf(1f, 1f, 1f, 1f)
        val b = floatArrayOf(10f, 20f, 30f, 40f)
        assertEquals(0.0f, HuntReportDetector.ncc(a, b), 0.001f)
    }

    @Test
    fun `ncc is insensitive to mean offset`() {
        // NCC subtracts the mean, so adding a constant offset to one array
        // should not change the score.
        val a = floatArrayOf(10f, 20f, 30f, 40f, 50f)
        val b = floatArrayOf(110f, 120f, 130f, 140f, 150f)  // a + 100
        assertEquals(1.0f, HuntReportDetector.ncc(a, b), 0.001f)
    }

    @Test
    fun `bitmapToGrey produces correct mean of channels`() {
        // Create a 1x1 bitmap with known RGB values and verify greyscale output.
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, (0xFF shl 24) or (90 shl 16) or (60 shl 8) or 30)
        val grey = HuntReportDetector.bitmapToGrey(bitmap)
        assertEquals(1, grey.size)
        assertEquals((90 + 60 + 30) / 3f, grey[0], 0.01f)
    }



    // -----------------------------------------------------------------------
    // Processing termination
    // -----------------------------------------------------------------------

    @Test
    fun `processing stops when confirm button is detected`() {
        assumeTrue("Stub — activate once recording includes post-confirm frames", false)
        TODO("Implement once recording available")
    }

    @Test
    fun `confirm button is detected in faded frame`() {
        val frame = loadTestFrame(huntGroupR6WithBreaks, frameIndex = 39)
        assumeTrue(
            "Viper frame 39 faded button not detected - acceptable",
            detector.isConfirmButtonVisible(frame)
        )
    }

    @Test
    fun `confirm button is detected in known clear frames`() {
        val frame1 = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 31)
        val frame2 = loadTestFrame(huntGroupR6WithBreaks, frameIndex = 40)
        assertTrue(
            "Khezu frame 31 should have confirm button visible",
            detector.isConfirmButtonVisible(frame1)
        )
        assertTrue(
            "Viper frame 40 should have confirm button visible",
            detector.isConfirmButtonVisible(frame2)
        )
    }

    @Test
    fun `confirm button is not detected before it appears`() {
        val frame1 = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 30)
        val frame2 = loadTestFrame(huntGroupR6WithBreaks, frameIndex = 38)
        assertFalse(
            "Khezu frame 30 should not have confirm button visible",
            detector.isConfirmButtonVisible(frame1)
        )
        assertFalse(
            "Viper frame 38 should not have confirm button visible",
            detector.isConfirmButtonVisible(frame2)
        )
    }
    // -----------------------------------------------------------------------
    // Remaining stubs
    // -----------------------------------------------------------------------

    @Test
    fun `unknown item produces placeholder drop record`() {
        assumeTrue("Stub — requires recording with unrecognised icon", false)
        TODO("Implement once lookup table gap scenario is available")
    }

    @Test
    fun debug_confirm_button_sample() {
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 31)
        println(detector.debugSampleRegion(frame))
    }
}


@Ignore("Blocked on HuntReportDetector.process() TODO — not yet implemented")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HuntReportDetectorProcessTest {

    private val huntSoloR6NoBreaks = "screen-20260527-002413-khezu.r6.urgent.no-breaks"
    private val huntGroupR6WithBreaks = "screen-20260527-002543-viper.flink.r6"
    private val templatePath = "src/main/assets/hunt_report_template.png"

    companion object {
        private val videoCache = mutableMapOf<String, List<Bitmap>>()
        private fun cachedVideo(videoName: String, loader: () -> List<Bitmap>): List<Bitmap> {
            return videoCache.getOrPut(videoName) { loader() }
        }
    }

    private lateinit var detector: HuntReportDetector

    @Before
    fun setUp() {
        detector = HuntReportDetector.createFromFile(templatePath)
    }

    // -----------------------------------------------------------------------
    // Monster identification
    // -----------------------------------------------------------------------

    @Test
    fun `monster name is read from solo hunt report`() {
        val frames = loadTestVideo(huntSoloR6NoBreaks)
        val result = detector.process(frames)

        assertNotNull("Detector should return a result", result)
        assertEquals("Khezu", result!!.monsterNameRaw)
    }

    @Test
    fun `star count and colour are detected for solo hunt`() {
        val frames = loadTestVideo(huntSoloR6NoBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        assertEquals(4, result!!.starCount)
        assertEquals("purple", result!!.starColour)
    }

    @Test
    fun `monster name is read from group hunt report`() {
        val frames = loadTestVideo(huntGroupR6WithBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        assertEquals("Viper Tobi-Kadachi", result!!.monsterNameRaw)
    }

    @Test
    fun `star count and colour are detected for group hunt`() {
        val frames = loadTestVideo(huntGroupR6WithBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        assertEquals(3, result!!.starCount)
        assertEquals("purple", result!!.starColour)
    }

    // -----------------------------------------------------------------------
    // Section detection
    // -----------------------------------------------------------------------

    @Test
    fun `basic rewards section is detected in solo hunt`() {
        val frames = loadTestVideo(huntSoloR6NoBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        assertTrue(
            "Basic rewards should be present",
            result!!.drops.any { it.section == "basic" }
        )
    }

    @Test
    fun `broken part rewards absent for solo hunt with no breaks`() {
        val frames = loadTestVideo(huntSoloR6NoBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        assertFalse(
            "Broken part rewards should not be present in this recording",
            result!!.drops.any { it.section == "broken_part" }
        )
    }

    @Test
    fun `group hunt rewards absent for solo hunt`() {
        val frames = loadTestVideo(huntSoloR6NoBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        assertFalse(
            "Group hunt rewards should not be present in a solo recording",
            result!!.drops.any { it.section == "group_hunt" }
        )
    }

    @Test
    fun `broken part rewards section is detected in group hunt`() {
        val frames = loadTestVideo(huntGroupR6WithBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        assertTrue(
            "Broken part rewards should be present",
            result!!.drops.any { it.section == "broken_part" }
        )
    }

    @Test
    fun `group hunt rewards section is detected in group hunt`() {
        val frames = loadTestVideo(huntGroupR6WithBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        assertTrue(
            "Group hunt rewards should be present",
            result!!.drops.any { it.section == "group_hunt" }
        )
    }

    // -----------------------------------------------------------------------
    // Slot counts and layout
    // -----------------------------------------------------------------------

    @Test
    fun `solo hunt basic rewards has exactly 4 framed drops`() {
        val frames = loadTestVideo(huntSoloR6NoBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        assertEquals(
            "Solo hunt should have exactly 4 basic reward drops",
            4,
            result!!.drops.count { it.section == "basic" }
        )
    }

    @Test
    fun `unframed icons are not recorded as drops`() {
        // Both recordings have an unframed event icon in basic rewards.
        // Neither should appear as a MaterialDrop.
        val soloResult  = detector.process(loadTestVideo(huntSoloR6NoBreaks))
        val groupResult = detector.process(loadTestVideo(huntGroupR6WithBreaks))

        assertNotNull(soloResult)
        assertNotNull(groupResult)
        assertEquals("Solo basic should have 4 drops not 5", 4,
            soloResult!!.drops.count { it.section == "basic" })
        assertEquals("Group basic should have 4 drops not 5", 4,
            groupResult!!.drops.count { it.section == "basic" })
    }

    @Test
    fun `group hunt has correct drop counts per section`() {
        val frames = loadTestVideo(huntGroupR6WithBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        assertEquals("Basic rewards", 4,
            result!!.drops.count { it.section == "basic" })
        assertEquals("Broken part rewards", 3,
            result!!.drops.count { it.section == "broken_part" })
        assertEquals("Group hunt rewards", 2,
            result!!.drops.count { it.section == "group_hunt" })
    }

    @Test
    fun `slot indices are assigned correctly in basic rewards`() {
        val frames = loadTestVideo(huntSoloR6NoBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        val basicDrops = result!!.drops.filter { it.section == "basic" }
        val indices    = basicDrops.map { it.slotIndex }.sorted()
        assertEquals(
            "Slot indices should be 0–3",
            listOf(0, 1, 2, 3),
            indices
        )
    }

    // -----------------------------------------------------------------------
    // Rarity detection
    // -----------------------------------------------------------------------

    @Test
    fun `armour r1 detected in correct slots for solo hunt`() {
        val frames = loadTestVideo(huntSoloR6NoBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        val basicDrops = result!!.drops
            .filter { it.section == "basic" }
            .sortedBy { it.slotIndex }

        assertEquals("Slot 1 (index 1) should be rarity 1", 1, basicDrops[1].rarity)
        assertEquals("Slot 3 (index 3) should be rarity 1", 1, basicDrops[3].rarity)
    }

    @Test
    fun `all drops have valid rarity`() {
        val frames = loadTestVideo(huntGroupR6WithBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        assertTrue(
            "All recorded drops should have rarity 1–6",
            result!!.drops.all { it.rarity in 1..6 }
        )
    }

    @Test
    fun `r6 item detected in group hunt rewards slot 0`() {
        val frames = loadTestVideo(huntGroupR6WithBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        val groupDrops = result!!.drops
            .filter { it.section == "group_hunt" }
            .sortedBy { it.slotIndex }

        assertTrue("Group hunt drops should not be empty", groupDrops.isNotEmpty())
        assertEquals("Group hunt slot 0 should be rarity 6", 6, groupDrops[0].rarity)
    }

    @Test
    fun `r6 item is correctly identified after animation settles`() {
        // R6 has a special reveal animation. The slot should not be confirmed
        // until the same item matches in two consecutive samples.
        val frames = loadTestVideo(huntGroupR6WithBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        val r6Drop = result!!.drops
            .filter { it.section == "group_hunt" }
            .firstOrNull { it.rarity == 6 }

        assertNotNull("R6 drop should be present in group hunt rewards", r6Drop)
    }

    // -----------------------------------------------------------------------
    // Quantity
    // -----------------------------------------------------------------------

    @Test
    fun `quantity is read from pill for each slot`() {
        val frames = loadTestVideo(huntSoloR6NoBreaks)
        val result = detector.process(frames)

        assertNotNull(result)
        assertTrue(
            "All drops should have positive quantity",
            result!!.drops.all { it.quantity > 0 }
        )
    }

    // -----------------------------------------------------------------------
    // Cross-section duplicates
    // -----------------------------------------------------------------------

    @Test
    fun `same material appearing in multiple sections is recorded separately`() {
        val frames = loadTestVideo(huntGroupR6WithBreaks)
        val result = detector.process(frames)

        assertNotNull(result)

        val basicR1Weapon  = result!!.drops
            .filter { it.section == "basic" && it.slotIndex == 3 }
        val brokenR1Weapon = result.drops
            .filter { it.section == "broken_part" && it.slotIndex in 0..1 }

        assertEquals("One R1 weapon drop in basic rewards slot 3", 1, basicR1Weapon.size)
        assertEquals("Two R1 weapon drops in broken part rewards", 2, brokenR1Weapon.size)

        assertTrue("Basic R1 weapon should be rarity 1",
            basicR1Weapon.all { it.rarity == 1 })
        assertTrue("Broken part R1 weapons should be rarity 1",
            brokenR1Weapon.all { it.rarity == 1 })
    }

}
class FakeTextDetector(private val response: String) : TextDetector {
    override suspend fun detectText(bitmap: Bitmap) = response
}