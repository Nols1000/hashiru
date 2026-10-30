package com.github.nols1000.hashiru.treadmill.domain

/** Why connecting to, pairing with or commanding the treadmill failed, in terms the user can act on. */
sealed interface TreadmillError {
    data object BluetoothOff : TreadmillError

    /** The user has not allowed the app to use Bluetooth. */
    data object BluetoothUnauthorized : TreadmillError
    data object BluetoothUnsupported : TreadmillError

    /** No WalkingPad turned up while scanning, or the picker found none. [code] is the platform's, for support. */
    data class PadNotFound(val code: Int? = null) : TreadmillError

    /** The chosen device answered but does not offer the WalkingPad service. */
    data object NotAWalkingPad : TreadmillError

    /** The stored association no longer resolves to a device, as after restoring the phone from a backup. */
    data object PairAgain : TreadmillError

    /** The device did not answer or the link broke during setup. [code] is the platform's, for support. */
    data class ConnectionFailed(val code: Int? = null) : TreadmillError

    data class CommandFailed(val command: TreadmillCommand) : TreadmillError
}

/** Thrown by the transport and association adapters; anything else they throw counts as [TreadmillError.ConnectionFailed]. */
class TreadmillException(val error: TreadmillError, message: String? = null) : Exception(message ?: error.toString())

/** Thrown by [com.github.nols1000.hashiru.treadmill.port.DeviceAssociationPort.associate] when the user dismisses the picker. */
class PairingCancelledException : Exception("Pairing was cancelled")
