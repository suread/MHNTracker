package com.readablesoftware.mhntracker.capture

import androidx.core.graphics.createBitmap
import com.readablesoftware.mhntracker.debug.DebugFrameSave
import com.readablesoftware.mhntracker.debug.FrameSaveFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Covers the FrameSaveFlow.RAW diagnostic (saveRawFrameIfEnabled) and
 * onDestroy teardown. MediaProjection setup, the producer/consumer frame
 * loop, and routeFrame's handler dispatch remain untested; out of scope here.
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
}
