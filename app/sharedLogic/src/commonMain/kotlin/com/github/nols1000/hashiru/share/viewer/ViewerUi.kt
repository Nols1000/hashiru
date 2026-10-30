package com.github.nols1000.hashiru.share.viewer

import com.github.nols1000.hashiru.share.domain.SharePhase
import com.github.nols1000.hashiru.workout.domain.Location

/** Something viewers should know beyond the numbers. */
sealed interface ViewerNote {
    /** Nothing arrived for a while; the phone may have no signal or no charge. */
    data class Silent(val minutes: Int) : ViewerNote

    /** Active, but the distance has not grown for a while. */
    data class NotMoving(val minutes: Int) : ViewerNote
    data class HeadingToCourse(val meters: Int) : ViewerNote
    data class OffCourse(val meters: Int) : ViewerNote
    data class OnCourse(val remainingMeters: Int) : ViewerNote
}

/** The share as the viewer shows it at one moment. */
data class ViewerUi(
    val connection: ViewerConnection,
    /** Null until the first snapshot. */
    val phase: SharePhase?,
    val outdoor: Boolean,
    /** Active time, running on between updates while the workout is active. */
    val seconds: Int,
    val meters: Int,
    val steps: Int,
    val paceSecondsPerKm: Int?,
    val heartRateBpm: Int?,
    val batteryPercent: Int?,
    val notes: List<ViewerNote>,
    /** Milliseconds since the last frame arrived; null before the first. */
    val updatedAgoMs: Long?,
)

/** Builds what the viewer shows from [state] at [nowMs]. */
fun viewerUi(state: ViewerState, nowMs: Long): ViewerUi {
    val latest = state.latest
    val phase = if (state.ended) SharePhase.Ended else latest?.phase
    val running = phase == SharePhase.Active
    val seconds = (latest?.totals?.seconds ?: 0) + if (running && latest != null) ((nowMs - latest.atMs) / 1000).coerceAtLeast(0).toInt() else 0
    val meters = latest?.totals?.meters ?: 0
    val notes = buildList {
        if (latest != null && phase != SharePhase.Ended) {
            val silentMs = nowMs - latest.atMs
            if (silentMs > SILENT_MS) add(ViewerNote.Silent(minutes(silentMs)))
            val stillMs = latest.lastMovedMs?.let { nowMs - it }
            if (running && stillMs != null && stillMs > STILL_MS) add(ViewerNote.NotMoving(minutes(stillMs)))
            latest.navigation?.let {
                add(
                    when {
                        !it.joined -> ViewerNote.HeadingToCourse(it.offCourseMeters)
                        !it.onCourse -> ViewerNote.OffCourse(it.offCourseMeters)
                        else -> ViewerNote.OnCourse(it.remainingMeters)
                    },
                )
            }
        }
    }
    return ViewerUi(
        connection = state.connection,
        phase = phase,
        outdoor = state.kind?.location != Location.Indoor,
        seconds = seconds,
        meters = meters,
        steps = latest?.totals?.steps ?: 0,
        paceSecondsPerKm = if (meters >= MIN_PACE_METERS) (latest!!.totals.seconds * 1000L / meters).toInt() else null,
        heartRateBpm = latest?.heartRateBpm,
        batteryPercent = latest?.batteryPercent,
        notes = notes,
        updatedAgoMs = state.receivedAtMs?.let { (nowMs - it).coerceAtLeast(0) },
    )
}

private fun minutes(ms: Long) = (ms / 60_000).toInt()

private const val SILENT_MS = 3 * 60_000L
private const val STILL_MS = 5 * 60_000L
private const val MIN_PACE_METERS = 50
