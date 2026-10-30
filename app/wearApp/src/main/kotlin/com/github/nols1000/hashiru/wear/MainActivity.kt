package com.github.nols1000.hashiru.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The wrist screen. Opening it also starts heart-rate tracking for a walk already in progress. */
class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = sensorPermissionGranted()
        if (permissionsGranted) ExerciseService.follow(this, wear.telemetry.value.phase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted = sensorPermissionGranted()
        setContent {
            WalkScreen(
                app = wear,
                permissionsGranted = permissionsGranted,
                onRequestPermissions = ::askForPermissions,
            )
        }
        if (!permissionsGranted) askForPermissions()
    }

    private fun askForPermissions() {
        requestPermissions.launch(
            arrayOf(sensorPermission(), Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    private fun sensorPermissionGranted() = checkSelfPermission(sensorPermission()) == PackageManager.PERMISSION_GRANTED

    private fun sensorPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
}
