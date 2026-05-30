package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import com.google.mlkit.common.MlKit
import androidx.test.core.app.ApplicationProvider
import com.readablesoftware.mhntracker.model.HuntResult
import com.readablesoftware.mhntracker.model.MaterialDrop
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    companion object {
        private val videoCache = mutableMapOf<String, List<Bitmap>>()  // lambda-free, just a map

        private fun cachedVideo(videoName: String, loader: () -> List<Bitmap>): List<Bitmap> {  // lambda: () -> List<Bitmap> is the loader function
            return videoCache.getOrPut(videoName) { loader() }  // lambda: { loader() } is the factory
        }
    }

    private lateinit var detector: HuntReportDetector

    @Before
    fun setUp() {
        detector = HuntReportDetector(textDetector = FakeTextDetector("Hunt Report"))
    }

    // -----------------------------------------------------------------------
    // Screen classification
    // -----------------------------------------------------------------------

    @Test
    fun `hunt report screen is recognised when text contains Hunt Report`() {
        detector = HuntReportDetector(textDetector = FakeTextDetector("Hunt Report"))
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 15)
        assertTrue(detector.isHuntReportScreen(frame))
    }

    @Test
    fun `hunt report screen is not recognised when text does not contain Hunt Report`() {
        detector = HuntReportDetector(textDetector = FakeTextDetector("Something Else"))
        val frame = loadTestFrame(huntSoloR6NoBreaks, frameIndex = 15)
        assertFalse(detector.isHuntReportScreen(frame))
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
        val soloResult = detector.process(loadTestVideo(huntSoloR6NoBreaks))
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
        val indices = basicDrops.map { it.slotIndex }.sorted()
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
        // Viper Tobi-Kadachi R1 weapon material appears in:
        //   basic rewards slot 3 (index 3)
        //   broken part rewards slots 0 and 1
        // These are three separate MaterialDrop records.
        val frames = loadTestVideo(huntGroupR6WithBreaks)
        val result = detector.process(frames)

        assertNotNull(result)

        val basicR1Weapon = result!!.drops
            .filter { it.section == "basic" && it.slotIndex == 3 }
        val brokenR1Weapon = result.drops
            .filter { it.section == "broken_part" && it.slotIndex in 0..1 }

        assertEquals("One R1 weapon drop in basic rewards slot 3", 1, basicR1Weapon.size)
        assertEquals("Two R1 weapon drops in broken part rewards", 2, brokenR1Weapon.size)

        // All three should have the same itemId once lookup table is populated.
        // For now assert rarity matches — all three should be rarity 1.
        assertTrue("Basic R1 weapon should be rarity 1",
            basicR1Weapon.all { it.rarity == 1 })
        assertTrue("Broken part R1 weapons should be rarity 1",
            brokenR1Weapon.all { it.rarity == 1 })
    }

    // -----------------------------------------------------------------------
    // Processing termination
    // -----------------------------------------------------------------------

    @Test
    fun `processing stops when confirm button is detected`() {
        assumeTrue("Stub — activate once recording includes post-confirm frames", false)
        TODO("Implement once recording available")
    }

    // -----------------------------------------------------------------------
    // Remaining stubs
    // -----------------------------------------------------------------------

    @Test
    fun `unknown item produces placeholder drop record`() {
        assumeTrue("Stub — requires recording with unrecognised icon", false)
        TODO("Implement once lookup table gap scenario is available")
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun assumeTrue(message: String, condition: Boolean) {
        Assume.assumeTrue(message, condition)
    }

    private fun loadTestFrame(videoName: String, frameIndex: Int): Bitmap {
        val path = "frames/$videoName/frame_${frameIndex.toString().padStart(4, '0')}.png"
        val stream = javaClass.classLoader!!.getResourceAsStream(path)
            ?: error("Test resource not found: $path")
        return BitmapFactory.decodeStream(stream)
            ?: error("Failed to decode bitmap from: $path")
    }

    /*
     * Initial video split into frames using ffmpeg
     * ffmpeg -i <filename>.mp4 -vf fps=2 frames/<filename>/frame_%04d.png
     * This matches intended sampling rate when run on device with MediaProjection
     * The consistent frames are then used for the tests
     */

    private fun loadTestVideo(videoName: String): List<Bitmap> {
        return cachedVideo(videoName) {  // lambda: the block is only called on first load
            val prefix = "frames/$videoName/"
            val resourceUrl = javaClass.classLoader!!.getResource(prefix)
                ?: return@cachedVideo emptyList()  // labeled return from the lambda
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }  // lambda
            File(resourceUrl.toURI())
                .listFiles { f -> f.extension == "png" }  // lambda
                ?.sortedBy { it.name }  // lambda
                ?.mapNotNull { file ->  // lambda
                    file.inputStream().use { stream ->  // lambda
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                }
                ?: emptyList()
        }
    }
}

class FakeTextDetector(private val response: String) : TextDetector {
    override fun detectText(bitmap: Bitmap) = response
}