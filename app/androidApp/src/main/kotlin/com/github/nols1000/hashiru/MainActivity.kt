package com.github.nols1000.hashiru

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import com.github.nols1000.hashiru.companion.adapter.MockWatchCompanionAdapter
import com.github.nols1000.hashiru.companion.engine.WatchRelay
import com.github.nols1000.hashiru.treadmill.adapter.AndroidCompanionAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.IntentSenderLauncher
import com.github.nols1000.hashiru.treadmill.adapter.MockAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.MockTransportAdapter
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.workout.adapter.HealthConnectAdapter
import com.github.nols1000.hashiru.workout.adapter.HealthPermissionLauncher
import com.github.nols1000.hashiru.workout.adapter.InMemoryKeyValueStore
import com.github.nols1000.hashiru.workout.adapter.MockHealthSyncAdapter
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
import com.github.nols1000.hashiru.workout.port.HealthAvailability
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    private var pendingPairing: CompletableDeferred<ActivityResult>? = null
    private val pairingSheet =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { pendingPairing?.complete(it) }

    private val pairingLauncher = IntentSenderLauncher { sender ->
        val result = CompletableDeferred<ActivityResult>()
        pendingPairing = result
        pairingSheet.launch(IntentSenderRequest.Builder(sender).build())
        result.await().takeIf { it.resultCode == RESULT_OK }?.data
    }

    private var pendingHealthPermissions: CompletableDeferred<Set<String>>? = null

    // The Health Connect library needs API 26 and Health Connect API 28; older versions never load it.
    private val healthPermissionSheet =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            registerForActivityResult(HealthConnectAdapter.createPermissionContract()) { pendingHealthPermissions?.complete(it) }
        } else {
            null
        }

    private val healthPermissionLauncher = HealthPermissionLauncher { permissions ->
        val sheet = healthPermissionSheet ?: return@HealthPermissionLauncher emptySet()
        val result = CompletableDeferred<Set<String>>()
        pendingHealthPermissions = result
        sheet.launch(permissions)
        result.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hashiru.health.launcher = healthPermissionLauncher

        val associations = hashiru.associations
        if (associations is AndroidCompanionAssociationAdapter) {
            associations.launcher = pairingLauncher
            requestMissingPermissions()
        }

        val platform = PlatformActions(
            openHealthSettings = hashiru.health.settingsIntent()?.let { intent -> { startActivity(intent) } },
            enableBluetooth = ::requestBluetooth,
            openAppSettings = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) },
            installHealthConnect = if (hashiru.health.availability() == HealthAvailability.NotInstalled) {
                // The onboarding link Health Connect documents for apps that find it missing.
                { openStoreListing("$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding") }
            } else {
                null
            },
            // The phone's listing offers to install on a connected watch too.
            installWatchApp = { openStoreListing(packageName) },
        )
        setContent {
            App(session = hashiru.session, workouts = hashiru.workouts, relay = hashiru.relay, platform = platform)
        }
    }

    override fun onDestroy() {
        val associations = hashiru.associations
        if (associations is AndroidCompanionAssociationAdapter && associations.launcher === pairingLauncher) {
            associations.launcher = null
        }
        pendingPairing?.cancel()
        if (hashiru.health.launcher === healthPermissionLauncher) hashiru.health.launcher = null
        pendingHealthPermissions?.cancel()
        super.onDestroy()
    }

    /** Shows the system's dialog to turn Bluetooth on; without the permission to ask, asks for that first. */
    private fun requestBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            return
        }
        try {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity turns Bluetooth on", e)
        }
    }

    private fun openStoreListing(query: String) {
        open(
            Intent(Intent.ACTION_VIEW, "market://details?id=$query".toUri()).setPackage(PLAY_STORE_PACKAGE),
            fallback = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$query".toUri()),
        )
    }

    private fun open(intent: Intent, fallback: Intent? = null) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            if (fallback != null) open(fallback) else Log.w(TAG, "Nothing handles $intent", e)
        }
    }

    private fun requestMissingPermissions() {
        val required = buildList {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> add(Manifest.permission.BLUETOOTH_CONNECT)
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O -> add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
    }
}

private const val TAG = "MainActivity"
private const val PLAY_STORE_PACKAGE = "com.android.vending"
private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

@Preview
@Composable
fun AppAndroidPreview() {
    val session = TreadmillSession(MockTransportAdapter(), MockAssociationAdapter())
    val workouts = WorkoutRecorder(session, MockHealthSyncAdapter(), InMemoryKeyValueStore())
    val relay = WatchRelay(session, workouts, MockWatchCompanionAdapter())
    // In dependency order, as the app does.
    session.start()
    workouts.start()
    relay.start()
    App(session = session, workouts = workouts, relay = relay)
}
