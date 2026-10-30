package com.github.nols1000.hashiru.workout.domain

import kotlin.math.abs

/** Totals for the week and the trends of the summary page, from walks read back from the health store. */
object WalkTrends {
    const val DAY_MS = 86_400_000L
    const val RECENT_DAYS = 7
    const val BASELINE_DAYS = 90

    /** Trends need this much history, so a new user's first week is not compared against itself. */
    const val MIN_HISTORY_DAYS = 14

    /** Changes within this fraction of the baseline count as steady. */
    private const val STEADY_FRACTION = 0.05
    private const val MIN_PACE_METERS = 50

    fun pace(seconds: Long, meters: Long): Int? = if (meters < MIN_PACE_METERS) null else (seconds * 1000 / meters).toInt()

    /**
     * Totals for the week whose day boundaries are [dayStartsMs]: the start of each day, then the start of the
     * next week. The platform supplies them, since days follow the local calendar and its time-zone changes.
     * A walk counts on the day it started.
     */
    fun week(walks: List<PastWalk>, dayStartsMs: List<Long>): WeekSummary {
        require(dayStartsMs.size >= 2) { "A week needs at least one day" }
        val inWeek = walks.filter { it.startMs >= dayStartsMs.first() && it.startMs < dayStartsMs.last() }
        val days = dayStartsMs.zipWithNext { start, end ->
            DayTotal(start, inWeek.filter { it.startMs in start..<end }.sumOf { it.distanceMeters })
        }
        return WeekSummary(
            days = days,
            distanceMeters = inWeek.sumOf { it.distanceMeters },
            durationSeconds = inWeek.sumOf { it.durationSeconds },
            steps = inWeek.sumOf { it.steps ?: 0 },
            walks = inWeek.size,
        )
    }

    /**
     * The last [RECENT_DAYS] days against the last [BASELINE_DAYS], or since the first walk when that is more recent.
     * Empty until there are [MIN_HISTORY_DAYS] of history. Pace is left out when either period has too little distance.
     */
    fun trends(walks: List<PastWalk>, nowMs: Long): List<WalkTrend> {
        val baseline = walks.filter { it.startMs in (nowMs - BASELINE_DAYS * DAY_MS)..nowMs }
        val oldest = baseline.minOfOrNull { it.startMs } ?: return emptyList()
        val baselineDays = ((nowMs - oldest + DAY_MS - 1) / DAY_MS).toInt().coerceAtMost(BASELINE_DAYS)
        if (baselineDays < MIN_HISTORY_DAYS) return emptyList()
        val recent = baseline.filter { it.startMs >= nowMs - RECENT_DAYS * DAY_MS }

        val recentMeters = recent.sumOf { it.distanceMeters.toLong() }
        val recentSeconds = recent.sumOf { it.durationSeconds.toLong() }
        val baselineMeters = baseline.sumOf { it.distanceMeters.toLong() }
        val baselineSeconds = baseline.sumOf { it.durationSeconds.toLong() }
        val recentPace = pace(recentSeconds, recentMeters)
        val baselinePace = pace(baselineSeconds, baselineMeters)
        return listOfNotNull(
            trend(TrendMetric.DailyDistance, (recentMeters / RECENT_DAYS).toInt(), (baselineMeters / baselineDays).toInt()),
            trend(TrendMetric.DailyDuration, (recentSeconds / RECENT_DAYS).toInt(), (baselineSeconds / baselineDays).toInt()),
            if (recentPace != null && baselinePace != null) trend(TrendMetric.Pace, recentPace, baselinePace) else null,
        )
    }

    private fun trend(metric: TrendMetric, recent: Int, baseline: Int): WalkTrend {
        val direction = when {
            abs(recent - baseline) <= baseline * STEADY_FRACTION -> TrendDirection.Steady
            recent > baseline -> TrendDirection.Up
            else -> TrendDirection.Down
        }
        return WalkTrend(metric, recent, baseline, direction)
    }
}
