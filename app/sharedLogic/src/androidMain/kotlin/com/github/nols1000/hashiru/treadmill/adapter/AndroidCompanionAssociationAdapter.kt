package com.github.nols1000.hashiru.treadmill.adapter

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.github.nols1000.hashiru.treadmill.domain.PairingCancelledException
import com.github.nols1000.hashiru.treadmill.domain.TreadmillError
import com.github.nols1000.hashiru.treadmill.domain.TreadmillException
import com.github.nols1000.hashiru.treadmill.port.DeviceAssociation
import com.github.nols1000.hashiru.treadmill.port.DeviceAssociationPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Launches the system pairing sheet; returns the result data, or null if the user cancelled. */
fun interface IntentSenderLauncher {
    suspend fun launch(sender: IntentSender): Intent?
}

/**
 * Pairing through [CompanionDeviceManager] (API 26+), which needs no location permission. The system
 * keeps the association, so it survives restarts without app storage. On API 31+ the system also
 * watches for the device and reports it to the app's `CompanionDeviceService`, which forwards to
 * [onPresenceChanged].
 *
 * [associate] needs an Activity to show the pairing sheet; set [launcher] while one is in the foreground.
 */
@SuppressLint("MissingPermission")
class AndroidCompanionAssociationAdapter(context: Context) : DeviceAssociationPort {
    private val appContext = context.applicationContext
    private val manager: CompanionDeviceManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.getSystemService(CompanionDeviceManager::class.java) else null

    @Volatile var launcher: IntentSenderLauncher? = null

    override val isAvailable: Boolean get() = manager != null

    private val _association = MutableStateFlow(storedAssociation())
    override val association: StateFlow<DeviceAssociation?> = _association.asStateFlow()

    private val _presenceChanges = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    override val presenceChanges: Flow<Boolean> = _presenceChanges

    init {
        // Idempotent; covers associations made by an earlier install of this code that did not observe presence yet.
        val stored = _association.value
        if (manager != null && stored != null) startObservingPresence(manager, stored.id)
    }

    /** Called by the app's `CompanionDeviceService` when the system reports the device appearing or leaving. */
    fun onPresenceChanged(nearby: Boolean) {
        Log.d(TAG, if (nearby) "Device appeared" else "Device disappeared")
        _presenceChanges.tryEmit(nearby)
    }

    override suspend fun associate(): DeviceAssociation {
        val manager = manager ?: error("Pairing needs Android 8 or later")
        val launcher = launcher ?: error("Pairing needs the app in the foreground")

        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("^(WalkingPad|KS-|KingSmith).*", Pattern.CASE_INSENSITIVE))
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()

        val sender = requestPairingSheet(manager, request)
        val result = launcher.launch(sender) ?: throw PairingCancelledException()
        val association = associationFrom(result) ?: throw TreadmillException(TreadmillError.PadNotFound(), "Pairing returned no device")
        Log.d(TAG, "Associated ${association.id}")

        _association.value = association
        startObservingPresence(manager, association.id)
        return association
    }

    override suspend fun disassociate() {
        val manager = manager ?: return
        val mac = _association.value?.id ?: return
        stopObservingPresence(manager, mac)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            infoFor(manager, mac)?.let { manager.disassociate(it.id) }
        } else {
            @Suppress("DEPRECATION")
            manager.disassociate(mac)
        }
        _association.value = null
    }

    private suspend fun requestPairingSheet(manager: CompanionDeviceManager, request: AssociationRequest): IntentSender =
        suspendCancellableCoroutine { continuation ->
            val callback = object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    if (continuation.isActive) continuation.resume(intentSender)
                }

                @Deprecated("Used below API 33")
                override fun onDeviceFound(intentSender: IntentSender) {
                    if (continuation.isActive) continuation.resume(intentSender)
                }

                override fun onFailure(error: CharSequence?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(TreadmillException(TreadmillError.PadNotFound(), error?.toString()))
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.associate(request, appContext.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                manager.associate(request, callback, Handler(Looper.getMainLooper()))
            }
        }

    private fun associationFrom(result: Intent): DeviceAssociation? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)?.toDeviceAssociation()
        } else {
            @Suppress("DEPRECATION")
            val scan = result.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)
            scan?.let { DeviceAssociation(id = it.device.address.uppercase(), displayName = it.scanRecord?.deviceName) }
        }

    private fun storedAssociation(): DeviceAssociation? {
        val manager = manager ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.myAssociations.firstNotNullOfOrNull { it.toDeviceAssociation() }
        } else {
            @Suppress("DEPRECATION")
            manager.associations.firstOrNull()?.let { DeviceAssociation(id = it.uppercase(), displayName = null) }
        }
    }

    private fun startObservingPresence(manager: CompanionDeviceManager, mac: String) {
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> infoFor(manager, mac)?.let {
                    manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(it.id).build())
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    @Suppress("DEPRECATION")
                    manager.startObservingDevicePresence(mac)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not observe device presence", e)
        }
    }

    private fun stopObservingPresence(manager: CompanionDeviceManager, mac: String) {
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> infoFor(manager, mac)?.let {
                    manager.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(it.id).build())
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    @Suppress("DEPRECATION")
                    manager.stopObservingDevicePresence(mac)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop observing device presence", e)
        }
    }

    @SuppressLint("NewApi")
    private fun infoFor(manager: CompanionDeviceManager, mac: String): AssociationInfo? =
        manager.myAssociations.firstOrNull { it.deviceMacAddress?.toString().equals(mac, ignoreCase = true) }

    @SuppressLint("NewApi")
    private fun AssociationInfo.toDeviceAssociation(): DeviceAssociation? =
        deviceMacAddress?.let { DeviceAssociation(id = it.toString().uppercase(), displayName = displayName?.toString()) }

    private companion object {
        const val TAG = "WalkingPadCdm"
    }
}
