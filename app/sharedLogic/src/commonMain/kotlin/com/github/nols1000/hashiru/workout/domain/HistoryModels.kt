package com.github.nols1000.hashiru.workout.domain

/**
 * A walking or running workout read back from the health store, from this app or any other source. Times are
 * epoch milliseconds.
 */
data class PastWalk(
    /** The health store's identifier for the workout. */
    val id: String,
    val startMs: Long,
    val endMs: Long,
    /** Time spent walking, without pauses. */
    val durationSeconds: Int,
    val distanceMeters: Int,
    /** Null when the source recorded no steps or reading them isn't allowed. */
    val steps: Int?,
    val activeKcal: Int?,
    val indoor: Boolean,
    val sourceName: String,
    /** The [WorkoutRecord.id] when this app recorded the walk; only those can be deleted. */
    val recordId: String?,
    val activity: Activity = Activity.Walking,
) {
    val recordedByApp: Boolean get() = recordId != null

    /** Seconds per kilometre, or null when the walk is too short for a meaningful pace. */
    val paceSecondsPerKm: Int? get() = WalkTrends.pace(durationSeconds.toLong(), distanceMeters.toLong())
}

/** Distance walked on one day of the week, starting at [startMs]. */
data class DayTotal(val startMs: Long, val distanceMeters: Int)

/** The current calendar week, Monday to Sunday or as the locale has it. */
data class WeekSummary(
    val days: List<DayTotal> = emptyList(),
    val distanceMeters: Int = 0,
    val durationSeconds: Int = 0,
    val steps: Int = 0,
    val walks: Int = 0,
)

enum class TrendMetric { DailyDistance, DailyDuration, Pace }

enum class TrendDirection { Up, Down, Steady }

/**
 * The last week against the longer baseline: meters or seconds per day, or seconds per kilometre for [TrendMetric.Pace],
 * where [TrendDirection.Down] is the improvement.
 */
data class WalkTrend(val metric: TrendMetric, val recent: Int, val baseline: Int, val direction: TrendDirection)
