package com.github.nols1000.hashiru.workout.domain

/** What the recorder tells the health store about a workout. Times are epoch milliseconds. */
sealed interface WorkoutEvent {
    /**
     * A workout is about to begin. A [WorkoutSource.Phone] workout begins only once the store answers
     * [HealthEvent.Tracking]; a [WorkoutSource.Pad] workout has already begun and needs no answer.
     */
    data class StartRequested(val id: String, val atMs: Long, val kind: WorkoutKind, val source: WorkoutSource) : WorkoutEvent
    data class Paused(val id: String, val atMs: Long) : WorkoutEvent

    /** [activeSeconds] is how long the workout was active up to then, for a store that lost count in a restart. */
    data class Resumed(val id: String, val atMs: Long, val activeSeconds: Int) : WorkoutEvent

    /**
     * A pad workout's distance and steps so far, as the pad counted them up to [atMs]. They are cumulative, so a
     * store adds what it has not added yet: a lost or repeated event does no harm.
     */
    data class Counted(val id: String, val atMs: Long, val meters: Int, val steps: Int) : WorkoutEvent

    /** The workout ended and should be saved; sent again to retry a save that did not go through. */
    data class Finished(val record: WorkoutRecord) : WorkoutEvent
    data class Discarded(val id: String, val reason: DiscardReason) : WorkoutEvent

    /** A workout in progress came back after process death. */
    data class Restored(val id: String, val startMs: Long, val kind: WorkoutKind, val source: WorkoutSource) : WorkoutEvent
}

enum class RejectReason {
    /** The user turned off motion access, which the phone needs to count steps and distance. */
    MotionAccessDenied,

    /** The user turned off location access, which an outdoor workout needs for its route and distance. */
    LocationAccessDenied,

    /** This device cannot record the kind of workout. */
    Unsupported,
}

/** What the health store reports back. */
sealed interface HealthEvent {
    val id: String

    /** The phone is tracking the workout; it begins now. */
    data class Tracking(override val id: String) : HealthEvent
    data class Rejected(override val id: String, val reason: RejectReason) : HealthEvent

    /**
     * The workout's totals so far, without pauses, as the store measures them. For a phone workout they include
     * distance and steps; what the store leaves out, the recorder takes from the pad or its own estimate.
     */
    data class Progress(override val id: String, val totals: TrackedTotals) : HealthEvent

    /** Positions newly recorded on an outdoor workout's route; none come while it is paused. */
    data class Route(override val id: String, val points: List<RoutePoint>) : HealthEvent

    /** The store stopped tracking the workout, such as when it could not be recovered after process death. */
    data class TrackingLost(override val id: String) : HealthEvent
    data class Saved(override val id: String) : HealthEvent

    /** The store cannot be written right now, as while the phone is locked; retried on the next foreground. */
    data class SaveDeferred(override val id: String) : HealthEvent
    data class SaveFailed(override val id: String, val message: String) : HealthEvent
}
