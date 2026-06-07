package com.readablesoftware.mhntracker.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

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
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
        } else {
            Toast.makeText(
                this,
                "Screen capture permission denied — hunt tracking inactive",
                Toast.LENGTH_SHORT
            ).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}