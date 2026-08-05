package com.readablesoftware.mhntracker.capture

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import com.readablesoftware.mhntracker.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PermissionTrampolineActivityTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    // -----------------------------------------------------------------------
    // Permission intent is fired on launch
    // -----------------------------------------------------------------------

    @Test
    @Ignore("Robolectric intercepts ActivityResultLauncher differently, so the real launch " +
            "can't be verified here. No instrumented companion exists yet either.")
    fun `activity fires screen capture intent on create`() {
        ActivityScenario.launch(PermissionTrampolineActivity::class.java).use {
            // TODO: no real assertion yet — see @Ignore reason above.
        }
    }

    // -----------------------------------------------------------------------
    // Denied result
    // -----------------------------------------------------------------------

    @Test
    fun `denied result shows toast`() {
        ActivityScenario.launch(PermissionTrampolineActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Simulate denied result by invoking the result callback directly
                val intent = Intent()
                activity.onPermissionResult(Activity.RESULT_CANCELED, intent)
            }
            val expectedMessage = context.getString(R.string.toast_capture_permission_denied)
            assertTrue(
                "Toast should be shown on denial",
                ShadowToast.showedToast(expectedMessage)
            )
        }
    }

    @Test
    fun `denied result does not start ScreenCaptureService`() {
        ActivityScenario.launch(PermissionTrampolineActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.onPermissionResult(Activity.RESULT_CANCELED, Intent())
            }
            val started = shadowOf(context).nextStartedService
            assertNull("ScreenCaptureService should not start on denial", started)
        }
    }

    @Test
    fun `denied result finishes activity`() {
        ActivityScenario.launch(PermissionTrampolineActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.onPermissionResult(Activity.RESULT_CANCELED, Intent())
                assertTrue("Activity should be finishing", activity.isFinishing)
            }
        }
    }
    // -----------------------------------------------------------------------
    // Granted result
    // -----------------------------------------------------------------------

    @Test
    @Ignore("Requires startForegroundService with a real MediaProjection token — not " +
            "obtainable under Robolectric. See granted_result_starts_ScreenCaptureService_with_correct_extras " +
            "in the instrumented test (also @Ignore'd — needs manual permission grant).")
    fun `granted result starts ScreenCaptureService`() {
        ActivityScenario.launch(PermissionTrampolineActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val data = Intent()
                activity.onPermissionResult(Activity.RESULT_OK, data)
            }
            // TODO: no real assertion yet — see @Ignore reason above.
        }
    }
}