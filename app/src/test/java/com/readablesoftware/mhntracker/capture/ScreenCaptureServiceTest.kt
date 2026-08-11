package com.readablesoftware.mhntracker.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.readablesoftware.mhntracker.debug.DebugFrameSave
import com.readablesoftware.mhntracker.debug.FrameSaveFlow
import com.readablesoftware.mhntracker.detection.FightEventDetector
import com.readablesoftware.mhntracker.detection.FightHandler
import com.readablesoftware.mhntracker.detection.FightStartDetector
import com.readablesoftware.mhntracker.detection.HandlerStatus
import com.readablesoftware.mhntracker.detection.HuntReportDetector
import com.readablesoftware.mhntracker.detection.SessionHandler
import com.readablesoftware.mhntracker.testutil.TestFrameLoader.loadTestFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory

/**
 * Covers the FrameSaveFlow.RAW diagnostic (saveRawFrameIfEnabled), onDestroy
 * teardown, and onStartCommand's early-return paths. MediaProjection setup,
 * the producer/consumer frame loop, and routeFrame's handler dispatch remain
 * untested; out of scope here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenCaptureServiceTest {

    private lateinit var service: ScreenCaptureService
    private lateinit var tempDirectory: File
    private lateinit var originalEnabled: Set<FrameSaveFlow>
    private lateinit var originalMediaProjectionActive: CaptureStatus

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        tempDirectory = createTempDirectory("screencaptureservice-test-").toFile()
        service = ScreenCaptureService().apply { baseDir = tempDirectory }
        originalEnabled = DebugFrameSave.enabled
        originalMediaProjectionActive = AppState.mediaProjectionActive.value
    }

    @After
    fun tearDown() {
        DebugFrameSave.enabled = originalEnabled
        AppState.setMediaProjectionActive(originalMediaProjectionActive)
        tempDirectory.deleteRecursively()
    }

    private fun frame() = createBitmap(4, 4)

    // Matches AppStateDetector's BLACK_SAMPLE_X2 x BLACK_SAMPLE_Y2 — the
    // minimum size isBlackScreen samples from. Also small enough to stay
    // below isMapScreen's compass-region size guard, so these frames can't
    // accidentally be read as a map screen.
    private fun blackFrame(): Bitmap =
        createBitmap(680, 1100).also { Canvas(it).drawColor(Color.BLACK) }

    private fun greyFrame(): Bitmap =
        createBitmap(680, 1100).also { Canvas(it).drawColor(Color.rgb(128, 128, 128)) }

    private fun tinyTemplateFile(name: String): String {
        val bitmap = createBitmap(4, 4)
        val file = File(tempDirectory, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.path
    }

    private fun rawFramesDir() = File(tempDirectory, "raw_frames")

    private fun rawFrameSessionDir(): File {
        val sessionDirs = rawFramesDir().listFiles { f -> f.isDirectory }
        assertEquals("expected exactly one raw frame session directory", 1, sessionDirs?.size)
        return sessionDirs!![0]
    }

    @Test
    fun `does not save when RAW flow is not enabled`() {
        DebugFrameSave.enabled = setOf(FrameSaveFlow.BREAK)

        service.saveRawFrameIfEnabled(frame())

        assertFalse("raw_frames directory should not be created", rawFramesDir().exists())
    }

    @Test
    fun `saves a frame file when RAW flow is enabled`() {
        DebugFrameSave.enabled = setOf(FrameSaveFlow.RAW)

        service.saveRawFrameIfEnabled(frame())

        val frames = rawFrameSessionDir().listFiles { f -> f.name.startsWith("frame_") }
        assertEquals(1, frames?.size)
        assertEquals("frame_0000.jpg", frames!![0].name)
    }

    @Test
    fun `successive frames are numbered sequentially in the same session directory`() {
        DebugFrameSave.enabled = setOf(FrameSaveFlow.RAW)

        repeat(3) { service.saveRawFrameIfEnabled(frame()) }

        val frameNames = rawFrameSessionDir().listFiles { f -> f.name.startsWith("frame_") }
            ?.map { it.name }
            ?.sorted()
        assertEquals(listOf("frame_0000.jpg", "frame_0001.jpg", "frame_0002.jpg"), frameNames)
    }

    @Test
    fun `saving stops once the frame cap is reached`() {
        DebugFrameSave.enabled = setOf(FrameSaveFlow.RAW)

        repeat(ScreenCaptureService.MAX_RAW_FRAMES + 5) {
            service.saveRawFrameIfEnabled(frame())
        }

        val frames = rawFrameSessionDir().listFiles { f -> f.name.startsWith("frame_") }
        assertEquals(ScreenCaptureService.MAX_RAW_FRAMES, frames?.size)
    }

    @Test
    fun `onDestroy on a never-started service does not throw and resets AppState to INACTIVE`() {
        AppState.setMediaProjectionActive(CaptureStatus.ACTIVE)

        service.onDestroy()

        assertEquals(CaptureStatus.INACTIVE, AppState.mediaProjectionActive.value)
    }

    @Test
    fun `onStartCommand with a null intent returns START_NOT_STICKY without starting a projection`() {
        AppState.setMediaProjectionActive(CaptureStatus.INACTIVE)

        val result = service.onStartCommand(null, 0, 0)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(CaptureStatus.INACTIVE, AppState.mediaProjectionActive.value)
    }

    @Test
    fun `onStartCommand with result code but no result data returns START_NOT_STICKY without starting a projection`() {
        AppState.setMediaProjectionActive(CaptureStatus.INACTIVE)
        val intent = Intent().putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, Activity.RESULT_OK)

        val result = service.onStartCommand(intent, 0, 0)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(CaptureStatus.INACTIVE, AppState.mediaProjectionActive.value)
    }

    @Test
    fun `onStartCommand with a non-OK result code returns START_NOT_STICKY without starting a projection`() {
        AppState.setMediaProjectionActive(CaptureStatus.INACTIVE)
        val intent = Intent().apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, Intent())
        }

        val result = service.onStartCommand(intent, 0, 0)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(CaptureStatus.INACTIVE, AppState.mediaProjectionActive.value)
    }

    private class FakeSessionHandler(
        private val triggers: Boolean = false,
        private val frameStatus: HandlerStatus = HandlerStatus.CONTINUE,
    ) : SessionHandler {
        var recognisesTriggerCalls = 0
        var onFrameCalls = 0
        var onTerminateCalls = 0
        override fun recognisesTrigger(frame: Bitmap): Boolean {
            recognisesTriggerCalls++; return triggers
        }
        override fun onFrame(frame: Bitmap): HandlerStatus {
            onFrameCalls++; return frameStatus
        }
        override fun onTerminate() { onTerminateCalls++ }
    }

    @Test
    fun `notificationTextFor a FightHandler returns Fight in progress`() {
        // Trigger detection is never exercised here, so throwaway templates
        // are sufficient — same approach as FightHandlerTest.
        val fightHandler = FightHandler(
            FightStartDetector.createFromFile(listOf(tinyTemplateFile("dummy_start.png"))),
            HuntReportDetector.createFromFile(
                tinyTemplateFile("dummy_report.png"),
                tinyTemplateFile("dummy_rewards.png"),
            ),
            FightEventDetector(),
            tempDirectory,
        )

        assertEquals("Fight in progress", service.notificationTextFor(fightHandler))
    }

    @Test
    fun `notificationTextFor any other handler returns Capture active`() {
        assertEquals("Capture active", service.notificationTextFor(FakeSessionHandler()))
    }

    // routeFrame / pollTriggers / handlers seam smoke test — just enough to
    // prove the plumbing works. Real coverage of black-screen filtering, map
    // detection, and trigger/dispatch behaviour lands in later tasks.
    @Test
    fun `handlers set via the seam are polled by routeFrame at the slow-check rate`() {
        val fake = FakeSessionHandler()
        service.handlers = listOf(fake)

        // SLOW_CHECK_EVERY_N_FRAMES is 3 — the first two calls are cheap
        // frames only, the third hits the slow-check branch.
        repeat(3) { service.routeFrame(frame()) }

        assertEquals(1, fake.recognisesTriggerCalls)
    }

    @Test
    fun `routeFrame skips all detection on a black frame`() {
        val fake = FakeSessionHandler()
        service.handlers = listOf(fake)

        // Black frames never advance the slow-check counter, so even
        // repeated calls should never reach trigger polling.
        repeat(3) { service.routeFrame(blackFrame()) }

        assertEquals(0, fake.recognisesTriggerCalls)
    }

    @Test
    fun `routeFrame polls handlers only on the Nth non-black frame`() {
        val fake = FakeSessionHandler()
        service.handlers = listOf(fake)

        // SLOW_CHECK_EVERY_N_FRAMES is 3 — the first two non-black frames
        // pass the pre-filter but stay below the slow-check rate.
        repeat(2) { service.routeFrame(greyFrame()) }
        assertEquals(0, fake.recognisesTriggerCalls)

        service.routeFrame(greyFrame())
        assertEquals(1, fake.recognisesTriggerCalls)
    }

    // Real map-screen capture already validated by AppStateDetectorTest's
    // MapDetectedTest — reused here rather than constructing a synthetic
    // compass fixture.
    private fun mapFrame(): Bitmap =
        loadTestFrame("map_detection/routine/positive", "frame_0000.png")

    @Test
    fun `routeFrame terminates the active handler when a map screen is detected`() {
        // Activation calls updateNotification, which needs a real attached
        // Context — the bare `service` field has none, so build via
        // Robolectric here (as task 4's onCreate tests do).
        val createdService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val fake = FakeSessionHandler(triggers = true)
        createdService.handlers = listOf(fake)
        // Activate directly via the pollTriggers seam — no need to drive the
        // slow-check counter just to get a handler active.
        createdService.pollTriggers(frame())
        assertEquals(fake, createdService.activeHandlerForTesting)

        val frame = mapFrame()
        // SLOW_CHECK_EVERY_N_FRAMES is 3 — the first two calls dispatch to
        // the active handler, the third hits the slow-check map detection.
        repeat(3) { createdService.routeFrame(frame) }

        assertEquals(1, fake.onTerminateCalls)
        assertNull(createdService.activeHandlerForTesting)
    }

    // pollTriggers / active-handler dispatch — needs a Robolectric-created
    // service since activation and DONE both call updateNotification, which
    // needs a real attached Context.

    @Test
    fun `routeFrame activates a triggering handler without forwarding the trigger frame to onFrame`() {
        AppState.setMediaProjectionActive(CaptureStatus.INACTIVE)
        val createdService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val fake = FakeSessionHandler(triggers = true)
        createdService.handlers = listOf(fake)

        // SLOW_CHECK_EVERY_N_FRAMES is 3 — trigger polling only runs on the
        // third call.
        repeat(3) { createdService.routeFrame(greyFrame()) }

        assertEquals(fake, createdService.activeHandlerForTesting)
        assertEquals(CaptureStatus.IN_FIGHT, AppState.mediaProjectionActive.value)
        assertEquals(0, fake.onFrameCalls)
    }

    @Test
    fun `routeFrame activates only the first-by-priority handler when two trigger on the same frame`() {
        AppState.setMediaProjectionActive(CaptureStatus.INACTIVE)
        val createdService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val first = FakeSessionHandler(triggers = true)
        val second = FakeSessionHandler(triggers = true)
        createdService.handlers = listOf(first, second)

        repeat(3) { createdService.routeFrame(greyFrame()) }

        assertEquals(first, createdService.activeHandlerForTesting)
        assertEquals(0, second.onFrameCalls)
        val loggedError = ShadowLog.getLogs().any {
            it.type == android.util.Log.ERROR && it.msg.contains("Multiple handlers triggered")
        }
        assertEquals(true, loggedError)
    }

    @Test
    fun `routeFrame dispatches to an already-active handler and keeps it active on CONTINUE`() {
        val createdService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val fake = FakeSessionHandler(triggers = true, frameStatus = HandlerStatus.CONTINUE)
        createdService.handlers = listOf(fake)
        createdService.pollTriggers(frame())
        assertEquals(fake, createdService.activeHandlerForTesting)

        createdService.routeFrame(greyFrame())

        assertEquals(1, fake.onFrameCalls)
        assertEquals(fake, createdService.activeHandlerForTesting)
    }

    @Test
    fun `routeFrame clears the active handler and resets AppState to ACTIVE on DONE`() {
        val createdService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val fake = FakeSessionHandler(triggers = true, frameStatus = HandlerStatus.DONE)
        createdService.handlers = listOf(fake)
        createdService.pollTriggers(frame())
        assertEquals(fake, createdService.activeHandlerForTesting)

        createdService.routeFrame(greyFrame())

        assertEquals(1, fake.onFrameCalls)
        assertNull(createdService.activeHandlerForTesting)
        assertEquals(CaptureStatus.ACTIVE, AppState.mediaProjectionActive.value)
    }

    // onCreate() via Robolectric's ServiceController — separate from the bare
    // `service` instance above, since onCreate needs a real Context (assets,
    // system services) to build handlers and post a notification.
    //
    // baseDir's filesDir fallback (getExternalFilesDir(null) == null) is not
    // covered: confirmed empirically that Robolectric's default test
    // environment always returns a non-null external files dir, so that
    // branch isn't reachable without additional shadowing.

    @Test
    fun `onCreate creates the notification channel at IMPORTANCE_LOW`() {
        val createdService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()

        val manager = createdService.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel("mhn_capture_channel")

        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `onCreate starts the service in the foreground with an Idle notification`() {
        val createdService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()

        val notification = shadowOf(createdService).lastForegroundNotification

        assertEquals("Idle", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
}
