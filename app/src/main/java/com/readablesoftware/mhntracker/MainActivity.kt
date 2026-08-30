package com.readablesoftware.mhntracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.readablesoftware.mhntracker.capture.AppState
import com.readablesoftware.mhntracker.capture.CaptureStatus
import com.readablesoftware.mhntracker.capture.MediaProjectionRequest
import com.readablesoftware.mhntracker.bubble.OverlayBubbleService
import com.readablesoftware.mhntracker.capture.ScreenCaptureService
import com.readablesoftware.mhntracker.capture.registerMediaProjectionLauncher
import com.readablesoftware.mhntracker.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val projectionLauncher = registerMediaProjectionLauncher()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleButton.setOnClickListener {
            if (AppState.captureStatus.value != CaptureStatus.INACTIVE) {
                stopService(Intent(this, ScreenCaptureService::class.java))
            } else {
                requestMediaProjectionPermission()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppState.captureStatus.collect {
                    updateUi(it)
                }
            }
        }

        ensureOverlayPermission()
    }

    private fun ensureOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayBubbleService::class.java)
        startForegroundService(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    private fun requestMediaProjectionPermission() {
        projectionLauncher.launch(MediaProjectionRequest.createScreenCaptureIntent(this))
    }

    private fun updateUi(status: CaptureStatus) {
        binding.toggleButton.text = when (status) {
            CaptureStatus.INACTIVE -> "Start Capture"
            else -> "Stop Capture"
        }
        binding.statusText.text = when (status) {
            CaptureStatus.INACTIVE   -> "Service stopped"
            CaptureStatus.ACTIVE   -> "Service running — waiting for hunt report"
            CaptureStatus.IN_FIGHT -> "Service running — capturing"
            CaptureStatus.FIGHT_TERMINATED -> "Service running — last fight terminated"
        }
    }
}