package com.github.nols1000.hashiru.treadmill.adapter

import com.github.nols1000.hashiru.treadmill.domain.PairingCancelledException
import com.github.nols1000.hashiru.treadmill.port.DeviceAssociation
import com.github.nols1000.hashiru.treadmill.port.DeviceAssociationPort
import com.github.nols1000.hashiru.treadmill.port.WalkingPadGatt
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import platform.AccessorySetupKit.ASAccessory
import platform.AccessorySetupKit.ASAccessoryEvent
import platform.AccessorySetupKit.ASAccessoryEventTypeAccessoryAdded
import platform.AccessorySetupKit.ASAccessoryEventTypeAccessoryChanged
import platform.AccessorySetupKit.ASAccessoryEventTypeAccessoryRemoved
import platform.AccessorySetupKit.ASAccessoryEventTypeActivated
import platform.AccessorySetupKit.ASAccessoryEventTypePickerDidDismiss
import platform.AccessorySetupKit.ASAccessorySession
import platform.AccessorySetupKit.ASDiscoveryDescriptor
import platform.AccessorySetupKit.ASPickerDisplayItem
import platform.CoreBluetooth.CBUUID
import platform.UIKit.UIImage
import platform.darwin.dispatch_get_main_queue

/**
 * Pairing through AccessorySetupKit: the system picker grants Bluetooth access to the chosen WalkingPad
 * only, without the app-wide Bluetooth permission prompt. The system keeps the pairing.
 *
 * iOS reports no presence changes; a pending connection in the transport waits for the pad instead.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAccessoryAssociationAdapter : DeviceAssociationPort {
    override val isAvailable: Boolean = true

    private val _association = MutableStateFlow<DeviceAssociation?>(null)
    override val association: StateFlow<DeviceAssociation?> = _association.asStateFlow()

    override val presenceChanges: Flow<Boolean> = emptyFlow()

    private val session = ASAccessorySession()
    private var pendingPick: CompletableDeferred<DeviceAssociation>? = null

    init {
        session.activateWithQueue(dispatch_get_main_queue()) { event -> event?.let(::onEvent) }
    }

    override suspend fun associate(): DeviceAssociation {
        // NSAccessorySetupBluetoothServices in Info.plist must list this exact string: the short form FE00 does not match.
        val byService = ASDiscoveryDescriptor()
        @Suppress("CAST_NEVER_SUCCEEDS")
        byService.setBluetoothServiceUUID(CBUUID.UUIDWithString(WalkingPadGatt.SERVICE_UUID) as objcnames.classes.CBUUID)
        // The picker misses the service until another app has connected to the pad, presumably because the pad sends it
        // only in the scan response; the company identifier matches straight away. Needs
        // NSAccessorySetupBluetoothCompanyIdentifiers in Info.plist.
        val byCompany = ASDiscoveryDescriptor()
        byCompany.bluetoothCompanyIdentifier = WalkingPadGatt.COMPANY_ID.toUShort()
        val image = UIImage.systemImageNamed("figure.walk.treadmill") ?: UIImage()
        val items = listOf(byService, byCompany).map { ASPickerDisplayItem(name = "WalkingPad", productImage = image, descriptor = it) }

        val pick = CompletableDeferred<DeviceAssociation>()
        pendingPick = pick
        session.showPickerForDisplayItems(items) { error ->
            if (error != null) pick.completeExceptionally(IllegalStateException(error.localizedDescription))
        }
        return try {
            pick.await()
        } finally {
            pendingPick = null
        }
    }

    override suspend fun disassociate() {
        val accessory = pairedAccessories().firstOrNull() ?: return
        val removed = CompletableDeferred<Unit>()
        session.removeAccessory(accessory) { error ->
            if (error == null) removed.complete(Unit) else removed.completeExceptionally(IllegalStateException(error.localizedDescription))
        }
        removed.await()
        _association.value = null
    }

    private fun onEvent(event: ASAccessoryEvent) {
        when (event.eventType) {
            ASAccessoryEventTypeActivated, ASAccessoryEventTypeAccessoryChanged ->
                _association.value = pairedAccessories().firstNotNullOfOrNull { it.toDeviceAssociation() }
            ASAccessoryEventTypeAccessoryAdded -> event.accessory?.toDeviceAssociation()?.let {
                _association.value = it
                pendingPick?.complete(it)
            }
            ASAccessoryEventTypeAccessoryRemoved -> _association.value = null
            // Sent after AccessoryAdded when the user picked a device, so anything still pending was cancelled.
            ASAccessoryEventTypePickerDidDismiss -> pendingPick?.completeExceptionally(PairingCancelledException())
        }
    }

    private fun pairedAccessories(): List<ASAccessory> = session.accessories.filterIsInstance<ASAccessory>()

    private fun ASAccessory.toDeviceAssociation(): DeviceAssociation? =
        bluetoothIdentifier?.let { DeviceAssociation(id = it.UUIDString, displayName = displayName) }
}
