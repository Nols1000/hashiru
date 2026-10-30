package com.github.nols1000.hashiru.treadmill.domain

/** Belt state as reported in byte 2 of a status packet. Only 0 and 1 are confirmed. */
sealed class BeltState(val code: Int) {
    data object Idle : BeltState(0)
    data object Running : BeltState(1)
    data class Other(val rawCode: Int) : BeltState(rawCode)

    companion object {
        fun fromCode(code: Int): BeltState = when (code) {
            0 -> Idle
            1 -> Running
            else -> Other(code)
        }
    }
}
