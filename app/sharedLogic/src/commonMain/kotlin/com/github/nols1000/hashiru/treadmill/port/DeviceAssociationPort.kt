package com.github.nols1000.hashiru.treadmill.port

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A treadmill the OS remembers for this app. [id] is what [TreadmillTransportPort.connect] takes as an address. */
data class DeviceAssociation(val id: String, val displayName: String?)

/** Driven port: OS-managed pairing (CompanionDeviceManager on Android, AccessorySetupKit on iOS). */
interface DeviceAssociationPort {
    /** False when the OS has no system pairing; the app then connects to the first matching device it finds. */
    val isAvailable: Boolean

    val association: StateFlow<DeviceAssociation?>

    /** Emits true when the associated device comes into range and false when it leaves, where the OS reports this. */
    val presenceChanges: Flow<Boolean>

    /** Shows the system device picker and stores the result. Throws if the user cancels or the picker fails. */
    suspend fun associate(): DeviceAssociation

    suspend fun disassociate()
}
