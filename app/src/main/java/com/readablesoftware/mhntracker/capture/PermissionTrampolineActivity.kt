package com.readablesoftware.mhntracker.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.readablesoftware.mhntracker.R

/**
 * Invisible trampoline Activity whose sole purpose is to request the
 * MediaProjection permission and start ScreenCaptureService on grant.
 *
 * Declared with a translucent theme and excludeFromRecents=true so it
 * is invisible to the user and does not appear in the recents list.
 * Finishes immediately after handling the permission result.
 */
class PermissionTrampolineActivity : AppCompatActivity() {

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onPermissionResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    // Internal visibility so Robolectric tests can invoke directly.
    internal fun onPermissionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(intent)
        } else {
            Toast.makeText(
                this,
                getString(R.string.toast_capture_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
        finish()
    }
}