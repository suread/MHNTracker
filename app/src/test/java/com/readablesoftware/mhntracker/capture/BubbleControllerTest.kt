package com.readablesoftware.mhntracker.capture

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BubbleControllerTest {

    private lateinit var context: Application
    private val activeFlow   = MutableStateFlow(false)
    private lateinit var controller: BubbleController

    @Before
    fun setUp() {
        context    = ApplicationProvider.getApplicationContext()
        controller = BubbleController(context, activeFlow)
    }

    // -----------------------------------------------------------------------
    // Inactive state — MediaProjection not running
    // -----------------------------------------------------------------------

    @Test
    fun `onTap when inactive launches PermissionTrampolineActivity`() {
        activeFlow.value = false
        controller.onTap()

        val started = shadowOf(context).nextStartedActivity
        assertNotNull("An activity should have been started", started)
        assert(started.component?.className == PermissionTrampolineActivity::class.java.name) {
            "Expected PermissionTrampolineActivity, got ${started.component?.className}"
        }
    }

    @Test
    fun `onTap when inactive sets NEW_TASK flag on launched intent`() {
        activeFlow.value = false
        controller.onTap()

        val started = shadowOf(context).nextStartedActivity
        assertNotNull(started)
        assert(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0) {
            "Intent should have FLAG_ACTIVITY_NEW_TASK set"
        }
    }

    @Test
    fun `onTap when inactive does not stop ScreenCaptureService`() {
        activeFlow.value = false
        controller.onTap()

        val stopped = shadowOf(context).nextStoppedService
        assertNull("No service should have been stopped", stopped)
    }

    // -----------------------------------------------------------------------
    // Active state — MediaProjection running
    // -----------------------------------------------------------------------

    @Test
    fun `onTap when active stops ScreenCaptureService`() {
        activeFlow.value = true
        controller.onTap()

        val stopped = shadowOf(context).nextStoppedService
        assertNotNull("A service should have been stopped", stopped)
        assert(stopped!!.component?.className == ScreenCaptureService::class.java.name) {
            "Expected ScreenCaptureService to be stopped, got ${stopped.component?.className}"
        }
    }

    @Test
    fun `onTap when active does not launch any activity`() {
        activeFlow.value = true
        controller.onTap()

        val started = shadowOf(context).nextStartedActivity
        assertNull("No activity should have been started", started)
    }

    // -----------------------------------------------------------------------
    // State change between taps
    // -----------------------------------------------------------------------

    @Test
    fun `onTap reflects current state at time of tap`() {
        activeFlow.value = false
        controller.onTap()
        val firstStarted = shadowOf(context).nextStartedActivity
        assertNotNull("First tap inactive — activity should start", firstStarted)

        // Clear shadow state and flip to active
        activeFlow.value = true
        controller.onTap()
        val stopped = shadowOf(context).nextStoppedService
        assertNotNull("Second tap active — service should stop", stopped)
    }
}