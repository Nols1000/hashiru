package com.github.nols1000.hashiru.share.domain

import com.github.nols1000.hashiru.course.domain.CoursePoint
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.WorkoutKind

/** Where a shared workout stands, as viewers see it. */
enum class SharePhase {
    /** The share began before the workout; viewers wait for it to start. */
    Waiting,
    Active,
    Paused,
    Ended,
}

/** Where the walker stands on the course they follow. All values are metres along or off the course. */
data class ShareNavigation(
    val progressMeters: Int,
    val remainingMeters: Int,
    val offCourseMeters: Int,
    val onCourse: Boolean,
    val finished: Boolean,
    val joined: Boolean,
)

/** A course as viewers draw it: simplified for the link, without the walker's own identifiers. */
data class SharedCourse(val name: String, val distanceMeters: Int, val points: List<CoursePoint>)

/**
 * The workout as of [atMs] (epoch milliseconds). [totals] are without pauses. [lastMovedMs] is when the distance
 * last grew noticeably, so viewers can tell a walker who stopped from one who paused; null before it first grows.
 */
data class ShareState(
    val atMs: Long,
    val phase: SharePhase,
    val totals: PadCounters,
    /** Null when the walker keeps heart rate to themselves, or none is measured. */
    val heartRateBpm: Int? = null,
    val batteryPercent: Int? = null,
    val lastMovedMs: Long? = null,
    val navigation: ShareNavigation? = null,
)

/** What a share sends; viewers take positions at or before the last they have as repeats. */
sealed interface ShareMessage {
    val state: ShareState

    /** The positions are the route's whole active part. */
    val route: List<RoutePoint>

    /** Everything a viewer needs who just opened the link. */
    data class Snapshot(
        val kind: WorkoutKind,
        val course: SharedCourse?,
        override val state: ShareState,
        override val route: List<RoutePoint>,
    ) : ShareMessage

    /** The state now and the positions since the last update the relay confirmed. */
    data class Update(override val state: ShareState, override val route: List<RoutePoint>) : ShareMessage
}

/** What the UI shows about live sharing. */
sealed interface LiveShareStatus {
    data object Off : LiveShareStatus

    /**
     * [link] goes to viewers as it is, including its fragment, which holds the keys. [watchers] counts the pages
     * open on it; [offline] is true while the relay cannot be reached.
     */
    data class Live(
        val link: String,
        val watchers: Int,
        val offline: Boolean,
        val includeHeartRate: Boolean,
    ) : LiveShareStatus

    /** The workout ended; viewers see its summary until the relay forgets the share. */
    data class Ended(val link: String) : LiveShareStatus

    /** The relay turned the share down, such as for a share too large or too long. */
    data object Failed : LiveShareStatus
}
