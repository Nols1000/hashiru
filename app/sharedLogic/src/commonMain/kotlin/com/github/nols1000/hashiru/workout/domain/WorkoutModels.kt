package com.github.nols1000.hashiru.workout.domain

/** A source's cumulative session counters (the pad's, or the phone's), or a difference between two readings of them. */
data class PadCounters(val seconds: Int = 0, val meters: Int = 0, val steps: Int = 0) {
    operator fun plus(other: PadCounters) = PadCounters(seconds + other.seconds, meters + other.meters, steps + other.steps)
    operator fun minus(other: PadCounters) = PadCounters(seconds - other.seconds, meters - other.meters, steps - other.steps)

    /** True when every counter is at least the one in [other]; false means the pad reset them. */
    fun continues(other: PadCounters) = seconds >= other.seconds && meters >= other.meters && steps >= other.steps

    companion object {
        val Zero = PadCounters()
    }
}

enum class PauseReason { BeltStopped, UserRequested, Disconnected }

enum class DiscardReason { TooShort, UserDiscarded }

enum class WeightSource { Health, Manual, Default }

data class BodyWeight(val kg: Double, val source: WeightSource) {
    companion object {
        const val DEFAULT_KG = 70.0
        val Default = BodyWeight(DEFAULT_KG, WeightSource.Default)
    }
}

enum class Sex { Female, Male }

/** What heart-rate energy and zones need besides weight; either part may be unknown. */
data class UserProfile(val ageYears: Int? = null, val sex: Sex? = null) {
    /** Fills the parts this profile lacks from [other]. */
    fun orElse(other: UserProfile) = UserProfile(ageYears ?: other.ageYears, sex ?: other.sex)
}

/** One window of active walking, written to the health store as its own samples. Times are epoch milliseconds. */
data class WorkoutSample(
    val startMs: Long,
    val endMs: Long,
    val distanceMeters: Int,
    val steps: Int,
    val activeKcal: Double,
    val totalKcal: Double,
    /** Null when no heart rate arrived during the window. */
    val heartRate: HeartRateSummary? = null,
)

data class WorkoutPause(val startMs: Long, val endMs: Long)

sealed interface SyncState {
    /** Not written yet: waiting to be saved or for the user to decide on health access. */
    data object Pending : SyncState

    /** The health store could not be written, as while the phone is locked; retried when the app comes to the foreground. */
    data object Busy : SyncState
    data object Saved : SyncState
    data class Failed(val message: String) : SyncState

    /** The user declined health access; the walk is kept on this device only. */
    data object NotAuthorized : SyncState

    /** This device has no health store. */
    data object Unavailable : SyncState
}

/** A finished workout. [id] doubles as the sync identifier, so writing the same record twice replaces it. */
data class WorkoutRecord(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val activeSeconds: Int,
    val distanceMeters: Int,
    val steps: Int,
    val activeKcal: Double,
    val totalKcal: Double,
    val weight: BodyWeight,
    val pauses: List<WorkoutPause>,
    val samples: List<WorkoutSample>,
    val syncState: SyncState = SyncState.Pending,
    val kind: WorkoutKind = WorkoutKind.IndoorWalk,
    val source: WorkoutSource = WorkoutSource.Pad,
) {
    val heartRateSeries: List<HeartRateSample> get() = samples.flatMap { it.heartRate?.series.orEmpty() }
    val avgHeartRate: Int? get() = heartRateSeries.takeIf { it.isNotEmpty() }?.let { s -> s.sumOf { it.bpm } / s.size }
    val maxHeartRate: Int? get() = samples.mapNotNull { it.heartRate?.maxBpm }.maxOrNull()

    /** True when some of the energy came from heart rate. */
    val energyFromHeartRate: Boolean get() = samples.any { it.heartRate?.usedForEnergy == true }
}
