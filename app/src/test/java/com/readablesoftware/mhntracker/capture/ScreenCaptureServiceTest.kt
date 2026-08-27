package com.readablesoftware.mhntracker.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.media.ImageReader
import androidx.core.graphics.createBitmap
import com.readablesoftware.mhntracker.capture.ScreenCaptureService.Companion.SLOW_CHECK_EVERY_N_FRAMES
import com.readablesoftware.mhntracker.debug.DebugFrameSave
import com.readablesoftware.mhntracker.debug.FrameSaveFlow
import com.readablesoftware.mhntracker.detection.AppStateDetector
import com.readablesoftware.mhntracker.detection.FightEventDetector
import com.readablesoftware.mhntracker.detection.FightHandler
import com.readablesoftware.mhntracker.detection.FightStartDetector
import com.readablesoftware.mhntracker.detection.HandlerStatus
import com.readablesoftware.mhntracker.detection.HuntReportDetector
import com.readablesoftware.mhntracker.detection.SessionHandler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.io.path.createTempDirectory

/**
 * Covers ScreenCaptureService's per-frame routing (routeFrame, pollTriggers,
 * captureFrame), lifecycle (onCreate, onDestroy), the FrameSaveFlow.RAW
 * diagnostic, and onStartCommand's early-return paths. MediaProjection /
 * VirtualDisplay setup and the real producer/consumer frame loop remain
 * untested — both need a real device/emulator.
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

    private fun tinyTemplateFile(name: String): String {
        val bitmap = createBitmap(4, 4)
        val file = File(tempDirectory, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.path
    }

    //region SAVING RAW DEBUG FRAMES
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
    fun `black frames are saved when debug capture is enabled`() {
        DebugFrameSave.enabled = setOf(FrameSaveFlow.RAW)
        service.appStateDetector = mockAppStateDetector()
        // stubbed true to ensure if black screen detection triggered before saving debug frame, this won't accidentally pass
        whenever(service.appStateDetector.isBlackScreen(any())).thenReturn(true)

        repeat(3) {
            service.saveRawFrameIfEnabled(frame())
        }

        val frames = rawFrameSessionDir().listFiles { f -> f.name.startsWith("frame_") }
        assertEquals(3, frames?.size)

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
    fun `if the screen capture is stopped and restarted, a new session directory is created`() {
        DebugFrameSave.enabled = setOf(FrameSaveFlow.RAW)

        val controller1 = Robolectric.buildService(ScreenCaptureService::class.java)
        val createdService1 = controller1.create().get().apply { baseDir = tempDirectory }
        repeat(3) { createdService1.saveRawFrameIfEnabled(frame()) { 1786549772230 }}
        controller1.destroy()

        val controller2 = Robolectric.buildService(ScreenCaptureService::class.java)
        val createdService2 = controller2.create().get().apply { baseDir = tempDirectory }
        repeat(2) { createdService2.saveRawFrameIfEnabled(frame()) { 1786549773230 } } // 1 second time gap

        val sessionDirs = rawFramesDir().listFiles { f -> f.isDirectory }
        assertEquals("expected exactly 2 raw frame session directories", 2, sessionDirs?.size)

        val frameNames1 = sessionDirs!![0].listFiles { f -> f.name.startsWith("frame_") }
            ?.map { it.name }
            ?.sorted()
        assertEquals(listOf("frame_0000.jpg", "frame_0001.jpg", "frame_0002.jpg"), frameNames1)
        val frameNames2 = sessionDirs[1].listFiles { f -> f.name.startsWith("frame_") }
            ?.map { it.name }
            ?.sorted()
        assertEquals(listOf("frame_0000.jpg", "frame_0001.jpg"), frameNames2)

    }
    //endregion

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

    //region ROUTEFRAME TESTS
    // routeFrame / pollTriggers / handlers seam smoke test — just enough to
    // prove the plumbing works. Black-screen filtering, map detection, and
    // trigger/dispatch behaviour are covered by the tests further below.

    //region ROUTING FRAMES TO HANDLERS
    @Test
    fun `handlers set via the seam are polled by routeFrame at the slow-check rate`() {
        val fake = FakeSessionHandler()
        service.handlers = listOf(fake)
        val appStateDetector = mockAppStateDetector()
        service.appStateDetector = appStateDetector

        // SLOW_CHECK_EVERY_N_FRAMES is 3 — the first two calls are cheap
        // frames only, the third hits the slow-check branch.
        repeat(SLOW_CHECK_EVERY_N_FRAMES) { service.routeFrame(frame()) }

        assertEquals(1, fake.recognisesTriggerCalls)
    }

    @Test
    fun `routeFrame activates a triggering handler without forwarding the trigger frame to onFrame`() {
        AppState.setMediaProjectionActive(CaptureStatus.INACTIVE)
        val createdService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val fake = FakeSessionHandler(triggers = true)
        createdService.handlers = listOf(fake)
        createdService.appStateDetector = mockAppStateDetector()

        // SLOW_CHECK_EVERY_N_FRAMES is 3 — trigger polling only runs on the
        // third call.
        repeat(SLOW_CHECK_EVERY_N_FRAMES) { createdService.routeFrame(frame()) }

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
        createdService.appStateDetector = mockAppStateDetector()


        repeat(SLOW_CHECK_EVERY_N_FRAMES) { createdService.routeFrame(frame()) }

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
        createdService.appStateDetector = mockAppStateDetector()

        createdService.pollTriggers(frame())
        assertEquals(fake, createdService.activeHandlerForTesting)

        createdService.routeFrame(frame())

        assertEquals(1, fake.onFrameCalls)
        assertEquals(fake, createdService.activeHandlerForTesting)
    }

    @Test
    fun `routeFrame clears the active handler and resets AppState to ACTIVE on DONE`() {
        val createdService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val fake = FakeSessionHandler(triggers = true, frameStatus = HandlerStatus.DONE)
        createdService.handlers = listOf(fake)
        createdService.appStateDetector = mockAppStateDetector()

        createdService.pollTriggers(frame())
        assertEquals(fake, createdService.activeHandlerForTesting)

        createdService.routeFrame(frame())

        assertEquals(1, fake.onFrameCalls)
        assertNull(createdService.activeHandlerForTesting)
        assertEquals(CaptureStatus.ACTIVE, AppState.mediaProjectionActive.value)
    }

    @Test
    fun `an active handler receives every frame, including if routed to slow-checks`() {
        val frame = frame()
        val createdService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val fake = FakeSessionHandler(triggers = true)
        createdService.handlers = listOf(fake)
        createdService.pollTriggers(frame)
        val appStateDetector = mockAppStateDetector()
        createdService.appStateDetector = appStateDetector
        assertEquals(fake, createdService.activeHandlerForTesting)

        repeat(SLOW_CHECK_EVERY_N_FRAMES) {
            createdService.routeFrame(frame)
        }

        assertEquals(SLOW_CHECK_EVERY_N_FRAMES, fake.onFrameCalls)
        verify(createdService.appStateDetector, times(1)).isMapScreen(any())

    }
    //endregion

    //region BLACK FRAME TESTING

    private fun numberOfFramesToMapCheck(): Int {
        // need to wrap the check variable since Mockito uses Java SAM interface
        val isMapScreenCalled = booleanArrayOf(false)

        whenever(service.appStateDetector.isMapScreen(any())).thenAnswer() { isMapScreenCalled[0] = true; false }

        var count = 0
        val frame = frame()
        while (!isMapScreenCalled[0] && count < SLOW_CHECK_EVERY_N_FRAMES * 2) {
            count++
            service.routeFrame(frame)
        }
        return count
    }

    @Test
    fun `routeFrame leaves the slow frame check count for map detection untouched when black frames are detected`() {
        val appStateDetector = mockAppStateDetector()
        service.handlers = listOf()
        service.appStateDetector = appStateDetector

        val mockBlackFrame = frame()
        val mockNotBlackFrame = frame()
        whenever(appStateDetector.isBlackScreen(mockBlackFrame)).thenReturn(true)
        whenever(appStateDetector.isBlackScreen(mockNotBlackFrame)).thenReturn(false)

        // test slow check cadence is expected value with no black frames before we start
        assertEquals(SLOW_CHECK_EVERY_N_FRAMES, numberOfFramesToMapCheck())

        for (i in 0 ..< SLOW_CHECK_EVERY_N_FRAMES) {
            repeat(i)  { service.routeFrame(mockNotBlackFrame) }
            service.routeFrame(mockBlackFrame)
            assertEquals(SLOW_CHECK_EVERY_N_FRAMES - i, numberOfFramesToMapCheck())
        }
    }

    private fun numberOfFramesToHandlerTriggerCheck(fake: FakeSessionHandler): Int {
        val triggersToStart = fake.recognisesTriggerCalls
        var count = 0
        val frame = frame()
        while ((fake.recognisesTriggerCalls - triggersToStart) == 0 && count < 6) {
            count++
            service.routeFrame(frame)
        }
        return count
    }

    @Test
    fun `routeFrame leaves the slow frame check count for handler triggers untouched when black frames are detected`() {
        val fake = FakeSessionHandler()
        val appStateDetector = mockAppStateDetector()
        service.appStateDetector = appStateDetector
        service.handlers = listOf(fake)

        val mockBlackFrame = frame()
        val mockNotBlackFrame = frame()
        whenever(appStateDetector.isBlackScreen(mockBlackFrame)).thenReturn(true)
        whenever(appStateDetector.isBlackScreen(mockNotBlackFrame)).thenReturn(false)

        // Black frames never advance the slow-check counter, so even
        // repeated calls should never reach trigger polling.
        repeat(SLOW_CHECK_EVERY_N_FRAMES + 1) { service.routeFrame(mockBlackFrame) }
        assertEquals(0, fake.recognisesTriggerCalls)

        // test slow check cadence is expected value with no black frames before we start
        assertEquals(SLOW_CHECK_EVERY_N_FRAMES, numberOfFramesToHandlerTriggerCheck(fake))

        for (i in 0 ..< SLOW_CHECK_EVERY_N_FRAMES) {
            repeat(i) { service.routeFrame(mockNotBlackFrame) }
            service.routeFrame(mockBlackFrame)
            assertEquals(SLOW_CHECK_EVERY_N_FRAMES - i, numberOfFramesToHandlerTriggerCheck(fake))
        }
    }

    @Test
    fun `routeFrame polls handlers only on the Nth non-black frame`() {
        val fake = FakeSessionHandler()
        service.handlers = listOf(fake)
        val appStateDetector = mockAppStateDetector()
        service.appStateDetector = appStateDetector

        // SLOW_CHECK_EVERY_N_FRAMES is 3 — the first two non-black frames
        // pass the pre-filter but stay below the slow-check rate.
        repeat(SLOW_CHECK_EVERY_N_FRAMES - 1) { service.routeFrame(frame()) }
        assertEquals(0, fake.recognisesTriggerCalls)

        service.routeFrame(frame())
        assertEquals(1, fake.recognisesTriggerCalls)
    }
    //endregion

    //region MAP SCREEN DETECTION

    private fun mockAppStateDetector(): AppStateDetector {
        val appStateDetector = mock<AppStateDetector>()
        whenever(appStateDetector.isBlackScreen(any())).thenReturn(false)
        whenever(appStateDetector.isMapScreen(any())).thenReturn(false)
        return appStateDetector
    }

    @Test
    fun `routeFrame uses slow check cadence for detecting map screens`() {
        val cadenceRepeat = 2
        val appStateDetector = mockAppStateDetector()
        service.appStateDetector = appStateDetector

        val frame = frame()
        service.handlers = listOf()

        repeat(SLOW_CHECK_EVERY_N_FRAMES * cadenceRepeat) {
            service.routeFrame(frame)
        }

        verify(appStateDetector, times(cadenceRepeat)).isMapScreen(any())
    }

    @Test
    fun `routeFrame does not send a frame to the active handler after that frame has been detected as map`() {
        val frame = frame()
        val appStateDetector = mockAppStateDetector()
        whenever(appStateDetector.isMapScreen(any())).thenReturn(true)

        val createdService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val fake = FakeSessionHandler(triggers = true)
        createdService.handlers = listOf(fake)
        createdService.appStateDetector = appStateDetector

        createdService.pollTriggers(frame)
        assertEquals(fake, createdService.activeHandlerForTesting)

        repeat(SLOW_CHECK_EVERY_N_FRAMES) {
            createdService.routeFrame(frame)
        }

        verify(appStateDetector, times(1)).isMapScreen(any())
        assertEquals(SLOW_CHECK_EVERY_N_FRAMES - 1, fake.onFrameCalls)
        assertEquals(1, fake.onTerminateCalls)
        assertNull(createdService.activeHandlerForTesting)
    }

    @Test
    fun `routeFrame does not send terminate to the active handler when a map screen check returns false`() {
        val frame = frame()
        val appStateDetector = mockAppStateDetector()

        // pollTriggers / active-handler dispatch — needs a Robolectric-created
        // service since activation and DONE both call updateNotification, which
        // needs a real attached Context.
        val createdService = Robolectric.buildService(ScreenCaptureService::class.java).create().get()
        val fake = FakeSessionHandler(triggers = true)
        createdService.handlers = listOf(fake)
        createdService.appStateDetector = appStateDetector

        createdService.pollTriggers(frame)
        assertEquals(fake, createdService.activeHandlerForTesting)

        repeat(SLOW_CHECK_EVERY_N_FRAMES) {
            createdService.routeFrame(frame)
        }

        verify(appStateDetector, times(1)).isMapScreen(any())
        assertEquals(SLOW_CHECK_EVERY_N_FRAMES, fake.onFrameCalls)
        assertEquals(0, fake.onTerminateCalls)
        assertEquals(fake, createdService.activeHandlerForTesting)

    }

    /*
    Currently production code will check for the map every 3 frames (that aren't black).
    This is wasteful as the purpose of detecting the map is to abort a handler that has completed its task but failed to detect its end point
    mapDetection should only happen if there is an active handler, and if there is it should continue
    to be tested in line with SLOW_CHECK_EVERY_N_FRAMES
     */
    @Ignore("Not implemented yet")
    @Test
    fun `routeFrame stops checking for the map after a positive map detection`() {}

    @Ignore("Not implemented yet")
    @Test
    fun `routeFrame stops checking for the map when active handler completes`() {}

    @Ignore("Not implemented yet")
    @Test
    fun `routeFrame does not check for the map if there is no active handler`() {}

    @Ignore("Not implemented yet")
    @Test
    fun `routeFrame starts checking for the map when there is an active handler`() {}

    //endregion

    //endregion routeFrame

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

    //region captureFrame
    // captureFrame() pixel math — mocks ImageReader/Image since Robolectric
    // has no real camera/projection pipeline to produce one.

    private fun rgbaBuffer(
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        pixels: Map<Pair<Int, Int>, IntArray> = emptyMap(),
    ): ByteBuffer {
        val data = ByteArray(rowStride * height)
        for ((coord, rgba) in pixels) {
            val (col, row) = coord
            val offset = row * rowStride + col * pixelStride
            for (i in 0 ..< 4) data[offset + i] = rgba[i].toByte()
        }
        return ByteBuffer.wrap(data)
    }

    private fun mockImage(width: Int, height: Int, pixelStride: Int, rowStride: Int, buffer: ByteBuffer): Image {
        val plane = mock<Image.Plane>()
        whenever(plane.pixelStride).thenReturn(pixelStride)
        whenever(plane.rowStride).thenReturn(rowStride)
        whenever(plane.buffer).thenReturn(buffer)
        val image = mock<Image>()
        whenever(image.planes).thenReturn(arrayOf(plane))
        whenever(image.width).thenReturn(width)
        whenever(image.height).thenReturn(height)
        return image
    }

    @Test
    fun `captureFrame returns a bitmap matching source dimensions when there is no row padding`() {
        val width = 4
        val height = 3
        val pixelStride = 4
        val rowStride = pixelStride * width
        val image = mockImage(width, height, pixelStride, rowStride, rgbaBuffer(height, rowStride, pixelStride))
        val imageReader = mock<ImageReader>()
        whenever(imageReader.acquireLatestImage()).thenReturn(image)
        service.imageReader = imageReader

        val bitmap = service.captureFrame()

        assertEquals(width, bitmap?.width)
        assertEquals(height, bitmap?.height)
        verify(image, times(1)).close()
    }

    @Test
    fun `captureFrame pads bitmap width for row stride and preserves pixel positions`() {
        val width = 4
        val height = 2
        val pixelStride = 4
        val rowStride = 24 // 8 bytes of padding beyond pixelStride * width (16)
        // Grayscale (R=G=B) markers so the assertions don't depend on which
        // byte-order Robolectric's native Skia binding happens to use on the
        // host platform for ARGB_8888 — only pixel *position* is under test.
        val buffer = rgbaBuffer(
            height, rowStride, pixelStride,
            mapOf(
                (2 to 0) to intArrayOf(90, 90, 90, 255),
                (5 to 1) to intArrayOf(200, 200, 200, 255), // within the padding-derived columns
            ),
        )
        val image = mockImage(width, height, pixelStride, rowStride, buffer)
        val imageReader = mock<ImageReader>()
        whenever(imageReader.acquireLatestImage()).thenReturn(image)
        service.imageReader = imageReader

        val bitmap = service.captureFrame()!!

        assertEquals(6, bitmap.width) // width + rowPadding / pixelStride = 4 + 8/4
        assertEquals(height, bitmap.height)
        assertEquals(Color.rgb(90, 90, 90), bitmap.getPixel(2, 0))
        assertEquals(Color.rgb(200, 200, 200), bitmap.getPixel(5, 1))
        // Neighboring, unmarked pixels stay at the buffer's zero-fill default.
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(3, 0))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(4, 1))
    }

    @Test
    fun `captureFrame closes the image exactly once even when an exception occurs`() {
        val image = mock<Image>()
        whenever(image.planes).thenThrow(RuntimeException("boom"))
        val imageReader = mock<ImageReader>()
        whenever(imageReader.acquireLatestImage()).thenReturn(image)
        service.imageReader = imageReader

        assertThrows(RuntimeException::class.java) { service.captureFrame() }

        verify(image, times(1)).close()
    }

    @Test
    fun `captureFrame returns null without throwing when acquireLatestImage returns null`() {
        val imageReader = mock<ImageReader>()
        whenever(imageReader.acquireLatestImage()).thenReturn(null)
        service.imageReader = imageReader

        assertNull(service.captureFrame())
    }
    //endregion
}
