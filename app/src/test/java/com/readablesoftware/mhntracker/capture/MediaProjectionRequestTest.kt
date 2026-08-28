package com.readablesoftware.mhntracker.capture

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.readablesoftware.mhntracker.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaProjectionRequestTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val deniedToast get() = context.getString(R.string.toast_capture_permission_denied)

    @Test
    fun `granted result starts ScreenCaptureService with the token extras`() {
        val token = Intent()

        MediaProjectionRequest.handleResult(context, Activity.RESULT_OK, token)

        val started = shadowOf(context).nextStartedService
        assertEquals(
            ScreenCaptureService::class.java.name,
            started.component?.className,
        )
        assertEquals(
            Activity.RESULT_OK,
            started.getIntExtra(ScreenCaptureService.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED),
        )
        assertEquals(
            token,
            started.getParcelableExtra<Intent>(ScreenCaptureService.EXTRA_RESULT_DATA),
        )
    }

    @Test
    fun `granted result returns true and shows no toast`() {
        assertTrue(MediaProjectionRequest.handleResult(context, Activity.RESULT_OK, Intent()))
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun `cancelled result shows the denied toast and starts nothing`() {
        val granted = MediaProjectionRequest.handleResult(context, Activity.RESULT_CANCELED, null)

        assertFalse(granted)
        assertNull(shadowOf(context).nextStartedService)
        assertTrue(ShadowToast.showedToast(deniedToast))
    }

    @Test
    fun `RESULT_OK with null data is treated as denied`() {
        val granted = MediaProjectionRequest.handleResult(context, Activity.RESULT_OK, null)

        assertFalse(granted)
        assertNull(shadowOf(context).nextStartedService)
        assertTrue(ShadowToast.showedToast(deniedToast))
    }

    @Test
    fun `createScreenCaptureIntent returns an intent`() {
        assertNotNull(MediaProjectionRequest.createScreenCaptureIntent(context))
    }
}
