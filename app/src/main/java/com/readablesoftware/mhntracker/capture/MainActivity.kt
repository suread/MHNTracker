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
    private val viewModel: CaptureViewModel by viewModels()  // lambda-free

    private val mediaProjectionManager by lazy {  // lambda: initialised on first access
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()  // lambda-free
    ) { result ->  // lambda: called when permission dialog is dismissed
        if (result.resultCode == RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleButton.setOnClickListener {  // lambda: click handler
            if (isServiceRunning()) {
                stopCaptureService()
            } else {
                requestMediaProjectionPermission()
            }
        }

        lifecycleScope.launch {  // lambda: coroutine observing state changes
            viewModel.state.collect { state ->  // lambda: called on each state change
                updateUi(state)
            }
        }
        requestBatteryOptimisationExemption()
    }

    override fun onResume() {
        super.onResume()
        if (!isServiceRunning() && viewModel.state.value != CaptureState.STOPPED) {
            viewModel.updateState(CaptureState.STOPPED)
        }
    }
    private fun requestMediaProjectionPermission() {
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {  // lambda
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
        return manager.getRunningServices(Int.MAX_VALUE)  // lambda-free
            .any { it.service.className == ScreenCaptureService::class.java.name }  // lambda
    }

    private fun updateUi(state: CaptureState) {
        binding.toggleButton.text = when (state) {  // lambda-free, when expression
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