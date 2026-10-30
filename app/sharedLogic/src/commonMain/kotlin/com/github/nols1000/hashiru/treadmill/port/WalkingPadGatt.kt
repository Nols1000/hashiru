package com.github.nols1000.hashiru.treadmill.port

/** GATT layout and advertisement matching shared by the BLE transports. */
object WalkingPadGatt {
    const val SERVICE_UUID = "0000fe00-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_CHARACTERISTIC_UUID = "0000fe01-0000-1000-8000-00805f9b34fb"
    const val WRITE_CHARACTERISTIC_UUID = "0000fe02-0000-1000-8000-00805f9b34fb"

    /** Bluetooth company identifier at the start of the pad's manufacturer data, which is in the advertisement itself. */
    const val COMPANY_ID = 0x0060

    private val namePrefixes = listOf("WalkingPad", "KS-", "KingSmith")

    fun matches(name: String?, advertisesService: Boolean): Boolean =
        advertisesService || (name != null && namePrefixes.any { name.startsWith(it, ignoreCase = true) })
}
