package com.readablesoftware.mhntracker.capture

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionTrampolineActivityInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    @Ignore("Requires real MediaProjection token from system permission dialog — not obtainable in automated tests. Verified manually by using the app.")
    fun granted_result_starts_ScreenCaptureService_with_correct_extras() {
        ActivityScenario.launch(PermissionTrampolineActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val data = Intent()
                activity.onPermissionResult(Activity.RESULT_OK, data)
            }

            // Allow the service start to propagate
            Thread.sleep(500)

            val activityManager = context.getSystemService(
                android.app.ActivityManager::class.java
            )
            val running = activityManager
                .getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == ScreenCaptureService::class.java.name }

            assertTrue(
                "ScreenCaptureService should be running after granted result",
                running
            )
        }
    }

    @Test
    @Ignore("Requires real MediaProjection token — see granted test above. Denial path covered by Robolectric PermissionTrampolineActivityTest.")
    fun denied_result_does_not_start_ScreenCaptureService() {
        ActivityScenario.launch(PermissionTrampolineActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.onPermissionResult(Activity.RESULT_CANCELED, null)
            }

            Thread.sleep(500)

            val activityManager = context.getSystemService(
                android.app.ActivityManager::class.java
            )
            val running = activityManager
                .getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == ScreenCaptureService::class.java.name }

            assertEquals(
                "ScreenCaptureService should not be running after denied result",
                false,
                running
            )

        }
    }
}