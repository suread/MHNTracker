package com.readablesoftware.mhntracker.capture

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Invisible Activity that runs the MediaProjection consent dialog for the
 * overlay bubble (a Service can't), then finishes. Translucent +
 * excludeFromRecents — see AndroidManifest.
 */
class PermissionTrampolineActivity : AppCompatActivity() {

    private val projectionLauncher = registerMediaProjectionLauncher { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionLauncher.launch(MediaProjectionRequest.createScreenCaptureIntent(this))
    }
}
