package com.readablesoftware.mhntracker.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.readablesoftware.mhntracker.R

/**
 * Shared MediaProjection permission handling for code that starts
 * [ScreenCaptureService].
 *
 * - launch [createScreenCaptureIntent] to show the system consent dialog
 * - pass the result to [handleResult]: if granted it starts [ScreenCaptureService]
 *   with the projection token, otherwise it shows a toast; returns whether
 *   permission was granted
 */
object MediaProjectionRequest {

    fun createScreenCaptureIntent(context: Context): Intent =
        (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .createScreenCaptureIntent()

    fun handleResult(context: Context, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_capture_permission_denied),
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }
        context.startForegroundService(
            Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
        )
        return true
    }
}

/**
 * Wraps the consent-dialog launch/result boilerplate. [onResult] runs after the
 * result is handled, with whether permission was granted, for the caller to
 * react — update UI, finish the Activity, etc.
 */
fun AppCompatActivity.registerMediaProjectionLauncher(
    onResult: (granted: Boolean) -> Unit = {},
): ActivityResultLauncher<Intent> {
    val contract = ActivityResultContracts.StartActivityForResult()
    return registerForActivityResult(contract) { result ->
        val granted = MediaProjectionRequest.handleResult(this, result.resultCode, result.data)
        onResult(granted)
    }
}
