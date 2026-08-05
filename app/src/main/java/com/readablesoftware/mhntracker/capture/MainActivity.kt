package com.readablesoftware.mhntracker.capture

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.readablesoftware.mhntracker.R
import com.readablesoftware.mhntracker.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: CaptureViewModel by viewModels()

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleButton.setOnClickListener {
            if (isServiceRunning()) {
                stopCaptureService()
            } else {
                requestMediaProjectionPermission()
            }
        }

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                updateUi(state)
            }
        }

        requestBatteryOptimisationExemption()
        ensureOverlayPermission()
    }

    private fun ensureOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayBubbleService::class.java)
        startForegroundService(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!isServiceRunning() && viewModel.state.value != CaptureState.STOPPED) {
            viewModel.updateState(CaptureState.STOPPED)
        }
        if (Settings.canDrawOverlays(this) && !isOverlayServiceRunning()) {
            startOverlayService()
        }
    }

    private fun isOverlayServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == OverlayBubbleService::class.java.name }
    }

    private fun requestMediaProjectionPermission() {
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(intent)
        viewModel.updateState(CaptureState.WAITING)
    }

    private fun stopCaptureService() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        viewModel.updateState(CaptureState.STOPPED)
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == ScreenCaptureService::class.java.name }
    }

    private fun updateUi(state: CaptureState) {
        binding.toggleButton.text = when (state) {
            CaptureState.STOPPED   -> "Start Capture"
            CaptureState.WAITING   -> "Stop Capture"
            CaptureState.CAPTURING -> "Stop Capture"
        }
        binding.statusText.text = when (state) {
            CaptureState.STOPPED   -> "Service stopped"
            CaptureState.WAITING   -> "Service running — waiting for hunt report"
            CaptureState.CAPTURING -> "Service running — capturing"
        }
    }

    private fun requestBatteryOptimisationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}