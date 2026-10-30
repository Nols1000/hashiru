package com.github.nols1000.hashiru.companion.domain

import com.github.nols1000.hashiru.workout.domain.HeartRateZone
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase

/** How far the watch app is from being usable. */
enum class CompanionState {
    /** No watch paired with the phone, or the platform has no watch link. */
    NotPaired,
    AppNotInstalled,

    /** Installed but out of radio range, asleep, or its app is not running. */
    Unreachable,
    Reachable,
}

/**
 * Commands from the wrist. Each is idempotent, so a retried or duplicated message does no harm: speed is an
 * absolute target and pausing names the wanted state instead of toggling.
 */
sealed interface WristCommand {
    data class SetSpeed(val tenthsKmh: Int) : WristCommand

    /** Pauses or resumes the walk; resuming when no walk is in progress starts the belt. */
    data class SetPaused(val paused: Boolean) : WristCommand

    /** Stops the belt at once. The walk pauses and ends on its own if the belt stays stopped. */
    data object StopBelt : WristCommand
}

/** A command as received, with the way to tell the watch whether it was carried out. */
class IncomingCommand(val command: WristCommand, val reply: (Boolean) -> Unit)

/** Everything the watch shows. */
data class WristTelemetry(
    val phase: WorkoutPhase = WorkoutPhase.Idle,
    val isConnected: Boolean = false,
    val isRunning: Boolean = false,
    val speedTenths: Int = 0,
    val durationSeconds: Int = 0,
    val distanceMeters: Int = 0,
    val heartRateBpm: Int? = null,
    val zone: HeartRateZone? = null,
    /** False while paused by a lost connection, where only the pad itself can resume. */
    val canResume: Boolean = false,
)
