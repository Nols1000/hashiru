package com.github.nols1000.hashiru.workout

import com.github.nols1000.hashiru.workout.domain.PastWalk
import com.github.nols1000.hashiru.workout.domain.TrendDirection
import com.github.nols1000.hashiru.workout.domain.TrendMetric
import com.github.nols1000.hashiru.workout.domain.WalkTrends
import com.github.nols1000.hashiru.workout.domain.WalkTrends.DAY_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalkTrendsTest {
    private val now = 1_700_000_000_000L
    private val weekStart = now - 2 * DAY_MS

    private fun walk(startMs: Long, meters: Int = 2_000, seconds: Int = 1_800, steps: Int? = 2_500) = PastWalk(
        id = "w$startMs", startMs = startMs, endMs = startMs + seconds * 1000L, durationSeconds = seconds,
        distanceMeters = meters, steps = steps, activeKcal = 70, indoor = true, sourceName = "hashiru", recordId = null,
    )

    @Test
    fun weekBucketsWalksByTheDayTheyStarted() {
        val days = (0..7).map { weekStart + it * DAY_MS }
        val walks = listOf(
            walk(weekStart + 1_000, meters = 1_000),
            walk(weekStart + 5_000, meters = 500, steps = null),
            walk(weekStart + DAY_MS + 1_000, meters = 3_000),
            walk(weekStart - 1_000, meters = 9_000), // last week
            walk(weekStart + 7 * DAY_MS, meters = 9_000), // next week
        )

        val week = WalkTrends.week(walks, days)

        assertEquals(listOf(1_500, 3_000, 0, 0, 0, 0, 0), week.days.map { it.distanceMeters })
        assertEquals(4_500, week.distanceMeters)
        assertEquals(3 * 1_800, week.durationSeconds)
        assertEquals(5_000, week.steps)
        assertEquals(3, week.walks)
    }

    @Test
    fun noTrendsBeforeTwoWeeksOfHistory() {
        val walks = (0 until 10).map { walk(now - it * DAY_MS) }
        assertTrue(WalkTrends.trends(walks, now).isEmpty())
    }

    @Test
    fun walkingMoreLatelyTrendsUpAndFasterPaceTrendsDown() {
        // A month of short, slow walks, then a week of longer, faster ones.
        val older = (8 until 30).map { walk(now - it * DAY_MS, meters = 1_000, seconds = 720) }
        val recent = (0 until 7).map { walk(now - it * DAY_MS - 1_000, meters = 3_000, seconds = 1_800) }

        val trends = WalkTrends.trends(older + recent, now).associateBy { it.metric }

        with(trends.getValue(TrendMetric.DailyDistance)) {
            assertEquals(3_000, this.recent)
            assertEquals(TrendDirection.Up, direction)
        }
        assertEquals(TrendDirection.Up, trends.getValue(TrendMetric.DailyDuration).direction)
        with(trends.getValue(TrendMetric.Pace)) {
            assertEquals(600, this.recent)
            assertEquals(TrendDirection.Down, direction)
        }
    }

    @Test
    fun anEvenRoutineIsSteady() {
        val walks = (0 until 60).map { walk(now - it * DAY_MS - 1_000) }

        val trends = WalkTrends.trends(walks, now)

        assertEquals(3, trends.size)
        assertTrue(trends.all { it.direction == TrendDirection.Steady }, "$trends")
    }

    @Test
    fun walksOlderThanTheBaselineAreIgnored() {
        val walks = listOf(walk(now - 200 * DAY_MS, meters = 50_000)) + (0 until 30).map { walk(now - it * DAY_MS - 1_000) }

        val distance = WalkTrends.trends(walks, now).first { it.metric == TrendMetric.DailyDistance }

        assertEquals(2_000, distance.baseline)
    }
}
