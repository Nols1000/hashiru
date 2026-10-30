package com.github.nols1000.hashiru.treadmill.domain

sealed interface TreadmillCommand {
    data object StartBelt : TreadmillCommand
    data object StopBelt : TreadmillCommand
    data object RequestStatusPing : TreadmillCommand

    /** Changes the belt speed while running; [tenthsKmh] is in 0.1 km/h units and must be positive. */
    data class SetSpeed(val tenthsKmh: Int) : TreadmillCommand {
        companion object {
            /** Speed bounds of the WalkingPad A1/C2 in 0.1 km/h units. */
            const val MIN_TENTHS = 5
            const val MAX_TENTHS = 60
        }
    }
}
