package com.readablesoftware.mhntracker.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_GRAPHIC_X1
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_GRAPHIC_X2
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_GRAPHIC_Y1
import com.readablesoftware.mhntracker.detection.FightScreenConstants.BREAK_GRAPHIC_Y2
import com.readablesoftware.mhntracker.detection.RewardsScreenConstants.CONFIRM_BGR
import com.readablesoftware.mhntracker.detection.RewardsScreenConstants.CONFIRM_SAMPLE_X1
import com.readablesoftware.mhntracker.detection.RewardsScreenConstants.CONFIRM_SAMPLE_X2
import com.readablesoftware.mhntracker.detection.RewardsScreenConstants.CONFIRM_SAMPLE_Y1
import com.readablesoftware.mhntracker.detection.RewardsScreenConstants.CONFIRM_SAMPLE_Y2
import com.readablesoftware.mhntracker.detection.RewardsScreenConstants.HUNT_REPORT_X1
import com.readablesoftware.mhntracker.detection.RewardsScreenConstants.HUNT_REPORT_X2
import com.readablesoftware.mhntracker.detection.RewardsScreenConstants.HUNT_REPORT_Y1
import com.readablesoftware.mhntracker.detection.RewardsScreenConstants.HUNT_REPORT_Y2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory
import com.readablesoftware.mhntracker.testutil.TestFrameLoader.loadTestFrame


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FightHandlerTest {

    // Same recordings/frame numbers as HuntReportDetectorTest — see
    // MHNTracker_HuntRewards_Decisions.md "Key Frame Numbers" table.
    private val huntSoloR6NoBreaks    = "screen-20260527-002413-khezu.r6.urgent.no-breaks"
    private val huntGroupR6WithBreaks = "screen-20260527-002543-viper.flink.r6"

    // Production template, loaded the same way as HuntReportDetectorTest —
    // relative to project root, the working directory for Gradle-run tests.
    private val templatePath = "src/main/assets/hunt_report_template.png"
    private val rewardsTemplatePath = "src/main/assets/rewards_template.png"

    private lateinit var handler: FightHandler
    private lateinit var tempDirectory: File

    // Large enough to contain every crop region touched by detection
    // (confirm button sample is the largest, at CONFIRM_SAMPLE_Y2).
    private val frameWidth = 1080
    private val frameHeight = 2400

    private fun tinyTemplateFile(dir: File, name: String): String {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val file = File(dir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.path
    }

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        tempDirectory = createTempDirectory("fighthandler-test-").toFile()

        // FightStartDetector/recognisesTrigger is never exercised via onFrame()
        // in these tests — onFrame() starts directly in WATCHING — so a
        // throwaway template for constructor is sufficient.
        val dummyTemplate = tinyTemplateFile(tempDirectory, "dummy.png")

        handler = FightHandler(
            FightStartDetector.createFromFile(listOf(dummyTemplate)),
            HuntReportDetector.createFromFile(templatePath, rewardsTemplatePath),
            FightEventDetector(),
            tempDirectory
        )
    }

    @After
    fun tearDown() {
        tempDirectory.deleteRecursively()
    }

    // BREAK_GRAPHIC region filled with a qualifying orange, satisfying both
    // the pixel-fraction and column-coverage thresholds in isBreakVisible().
    private fun breakVisibleFrame(): Bitmap {
        val frame = createBitmap(frameWidth, frameHeight)
        Canvas(frame).apply {
            drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.rgb(220, 150, 60) }
            drawRect(
                BREAK_GRAPHIC_X1.toFloat(), BREAK_GRAPHIC_Y1.toFloat(),
                BREAK_GRAPHIC_X2.toFloat(), BREAK_GRAPHIC_Y2.toFloat(), paint
            )
        }
        return frame
    }

    // Confirm button sample region filled with the exact confirm button colour,
    // satisfying isConfirmButtonVisible().
    private fun confirmVisibleFrame(): Bitmap {
        val frame = createBitmap(frameWidth, frameHeight)
        Canvas(frame).apply {
            drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.rgb(CONFIRM_BGR[2], CONFIRM_BGR[1], CONFIRM_BGR[0]) }
            drawRect(
                CONFIRM_SAMPLE_X1.toFloat(), CONFIRM_SAMPLE_Y1.toFloat(),
                CONFIRM_SAMPLE_X2.toFloat(), CONFIRM_SAMPLE_Y2.toFloat(), paint
            )
        }
        return frame
    }

    @Test
    fun `break crops and report frames are saved into the same session directory`() {

        handler.onFrame(breakVisibleFrame())                                  // WATCHING: break stored via composer
        handler.onFrame(loadTestFrame(huntSoloR6NoBreaks, frameIndex = 15))  // Hunt Report visible -> CAPTURING
        handler.onFrame(loadTestFrame(huntSoloR6NoBreaks, frameIndex = 31))  // Confirm visible -> session ends

        val sessionsDir = File(tempDirectory, "sessions")
        val sessionDirs = sessionsDir.listFiles { f -> f.isDirectory }
        assertEquals("exactly one session directory should be created", 1, sessionDirs?.size)

        val sessionDir = sessionDirs!![0]
        val reportFrames = sessionDir.listFiles { f -> f.name.startsWith("frame_") }
        val breakCropsFiles = sessionDir.listFiles { f -> f.name.startsWith("break_crops") }

        assertTrue("report frames should be saved", (reportFrames?.size ?: 0) > 0)
        assertEquals("exactly one break crops export should be saved", 1, breakCropsFiles?.size)
    }

    @Test
    fun `session directory is reused across multiple report frames in one fight`() {

        handler.onFrame(loadTestFrame(huntSoloR6NoBreaks, frameIndex = 15))  // WATCHING -> CAPTURING, saves frame 0
        handler.onFrame(loadTestFrame(huntSoloR6NoBreaks, frameIndex = 15))  // still CAPTURING, saves frame 1 (confirm not visible on this frame)
        handler.onFrame(loadTestFrame(huntSoloR6NoBreaks, frameIndex = 31))  // Confirm visible -> session ends

        val sessionsDir = File(tempDirectory, "sessions")
        val sessionDirs = sessionsDir.listFiles { f -> f.isDirectory }
        assertEquals("all frames from one fight should share a single session directory", 1, sessionDirs?.size)

        val reportFrames = sessionDirs!![0].listFiles { f -> f.name.startsWith("frame_") }
        assertEquals(3, reportFrames?.size)
    }

    @Test
    fun `hunt report through confirm button produces session with report frames and export, Khezu recording`() {
        handler.onFrame(
            loadTestFrame(
                huntSoloR6NoBreaks,
                frameIndex = 15
            )
        )  // Hunt Report visible -> CAPTURING
        handler.onFrame(
            loadTestFrame(
                huntSoloR6NoBreaks,
                frameIndex = 31
            )
        ) // Confirm visible -> session ends

        val sessionsDir = File(tempDirectory, "sessions")
        val sessionDirs = sessionsDir.listFiles { f -> f.isDirectory }
        assertEquals("exactly one session directory should be created", 1, sessionDirs?.size)

        val sessionDir = sessionDirs!![0]
        val reportFrames    = sessionDir.listFiles { f -> f.name.startsWith("frame_") }
        val breakCropsFiles = sessionDir.listFiles { f -> f.name.startsWith("break_crops") }

        assertTrue("report frames should be saved", (reportFrames?.size ?: 0) > 0)
        assertEquals("export runs even with zero breaks recorded", 1, breakCropsFiles?.size)
    }

    @Test
    fun `hunt report through confirm button produces session with report frames and export, Viper recording`() {
        handler.onFrame(
            loadTestFrame(
                huntGroupR6WithBreaks,
                frameIndex = 15
            )
        ) // Hunt Report visible -> CAPTURING
        handler.onFrame(
            loadTestFrame(
                huntGroupR6WithBreaks,
                frameIndex = 40
            )
        ) // Confirm visible -> session ends

        val sessionsDir = File(tempDirectory, "sessions")
        val sessionDirs = sessionsDir.listFiles { f -> f.isDirectory }
        assertEquals("exactly one session directory should be created", 1, sessionDirs?.size)

        val sessionDir = sessionDirs!![0]
        val reportFrames    = sessionDir.listFiles { f -> f.name.startsWith("frame_") }
        val breakCropsFiles = sessionDir.listFiles { f -> f.name.startsWith("break_crops") }

        assertTrue("report frames should be saved", (reportFrames?.size ?: 0) > 0)
        assertEquals("export runs even with zero breaks recorded", 1, breakCropsFiles?.size)
    }

    // ------------------------------------------------------------------
    // PLACEHOLDER — break detection uses a synthetic frame, not a real one.
    //
    // Neither Khezu nor Viper recordings contain a BREAK event (both were
    // captured focusing on the reward screen only), and the Tzitzi recording
    // has no breaks either. No real frame with the BREAK graphic fully
    // rendered is currently available in test fixtures.
    //
    // This test uses a synthetic frame with a plain orange fill in the
    // BREAK_GRAPHIC region, satisfying FightEventDetector's pixel-fraction
    // and column-coverage thresholds artificially. It proves the WATCHING
    // state correctly routes a detected break into the composer, but does
    // NOT prove real game frames trigger detection correctly — that still
    // needs validating against an actual capture.
    //
    // TODO: replace with a real frame once a break-containing recording is
    // available, following the same loadTestFrame pattern as the tests above.
    // ------------------------------------------------------------------
    @Test
    fun `break detected during WATCHING is stored and exported (placeholder synthetic frame)`() {
        val breakFrame = createBitmap(1080, 2400)
        Canvas(breakFrame).apply {
            drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.rgb(220, 150, 60) }
            drawRect(
                BREAK_GRAPHIC_X1.toFloat(), BREAK_GRAPHIC_Y1.toFloat(),
                BREAK_GRAPHIC_X2.toFloat(), BREAK_GRAPHIC_Y2.toFloat(), paint
            )
        }

        handler.onFrame(breakFrame)                                             // WATCHING: break stored
        handler.onFrame(
            loadTestFrame(
                huntSoloR6NoBreaks,
                frameIndex = 15
            )
        )     // -> CAPTURING
        handler.onFrame(
            loadTestFrame(
                huntSoloR6NoBreaks,
                frameIndex = 31
            )
        )     // confirm -> session ends

        val sessionsDir = File(tempDirectory, "sessions")
        val sessionDir  = sessionsDir.listFiles { f -> f.isDirectory }!![0]
        val breakCropsFiles = sessionDir.listFiles { f -> f.name.startsWith("break_crops") }

        assertEquals(1, breakCropsFiles?.size)
        // Not asserting composite content here — that's BreakCropComposerTest's
        // job. This only checks FightHandler correctly routes a break into it.
    }

    // ------------------------------------------------------------------
    // Trigger-classification diagnostic — see FightHandler class doc.
    // Every capture is logged as TITLE_ONLY, REWARDS_ONLY, or BOTH, so
    // frequency of each can be compared once real data accumulates.
    //
    // Fixtures: real frames from an independent solo hunt (Malzeno), not
    // used elsewhere:
    //   frame_0000                  — title visible, Rewards not yet rendered
    //   frame_0005                  — both visible
    //   frame_0005_title_occluded   — synthetic: title painted over, Rewards
    //                                 visible (simulates a stacked pop-up;
    //                                 no real example was available)
    // ------------------------------------------------------------------

    private fun lastLogLine(): String {
        val logFile = File(tempDirectory, "hunt_report_trigger_log.log")
        assertTrue("trigger log should be written for every completed session", logFile.exists())
        return logFile.readLines().last()
    }

    @Test
    fun `session is classified TITLE_ONLY when Rewards never appears`() {
        handler.onFrame(loadTestFrame("hunt-report-rewards", frameIndex = 0))  // title only -> CAPTURING
        handler.onFrame(loadTestFrame("hunt-report-rewards", frameIndex = 0))  // still capturing, Rewards still not visible
        handler.onFrame(confirmVisibleFrame())                                 // confirm -> session ends, Rewards never seen

        assertTrue("expected TITLE_ONLY classification", lastLogLine().contains("TITLE_ONLY"))
    }

    @Test
    fun `session is classified BOTH when Rewards appears later in the same capture`() {
        handler.onFrame(loadTestFrame("hunt-report-rewards", frameIndex = 0))  // title only -> CAPTURING
        handler.onFrame(loadTestFrame("hunt-report-rewards", frameIndex = 5))  // Rewards now visible too
        handler.onFrame(confirmVisibleFrame())                                 // confirm -> session ends

        assertTrue("expected BOTH classification", lastLogLine().contains("BOTH"))
    }

    @Test
    fun `session is classified BOTH when title and Rewards trigger on the same frame`() {
        handler.onFrame(loadTestFrame("hunt-report-rewards", frameIndex = 5))  // both visible -> CAPTURING
        handler.onFrame(confirmVisibleFrame())                                 // confirm -> session ends

        assertTrue("expected BOTH classification", lastLogLine().contains("BOTH"))
    }

    @Test
    fun `session is classified REWARDS_ONLY when title is occluded for the whole capture`() {
        handler.onFrame(
            loadTestFrame("hunt-report-rewards", "frame_0005_title_occluded.png")
        )  // title occluded, Rewards visible -> CAPTURING via Rewards only
        handler.onFrame(confirmVisibleFrame())  // confirm -> session ends, title never seen

        assertTrue("expected REWARDS_ONLY classification", lastLogLine().contains("REWARDS_ONLY"))
    }

    @Test
    fun `trigger log accumulates one line per completed session`() {
        handler.onFrame(loadTestFrame("hunt-report-rewards", frameIndex = 5))  // BOTH
        handler.onFrame(confirmVisibleFrame())

        handler.onFrame(loadTestFrame("hunt-report-rewards", frameIndex = 0))  // TITLE_ONLY
        handler.onFrame(confirmVisibleFrame())

        val logFile = File(tempDirectory, "hunt_report_trigger_log.log")
        assertEquals(2, logFile.readLines().size)
    }
}