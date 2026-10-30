package com.github.nols1000.hashiru.treadmill.domain

/** Discrete facts about the treadmill. Continuous telemetry is exposed as state instead, see [TelemetrySnapshot]. */
sealed interface TreadmillEvent {
    data class BeltStateChanged(val state: BeltState) : TreadmillEvent

    /** The associated device came into radio range. */
    data object DeviceAppearedNearby : TreadmillEvent

    /** The associated device left radio range or was switched off. */
    data object DeviceDisappeared : TreadmillEvent

    /** The OS handed back a connection that a previous app process had established. */
    data object SessionRestoredInBackground : TreadmillEvent
}
