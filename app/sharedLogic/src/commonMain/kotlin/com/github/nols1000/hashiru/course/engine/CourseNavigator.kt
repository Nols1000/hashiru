package com.github.nols1000.hashiru.course.engine

import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.domain.CourseGeometry
import com.github.nols1000.hashiru.course.domain.NavigationStatus
import kotlin.math.roundToInt

/**
 * Follows a workout along [course], one position at a time. Pure apart from its own state, so every rule is
 * unit-testable.
 *
 * - Progress is matched near where the workout last was on the course, from [BACK_M] behind to [AHEAD_M] ahead,
 *   so an out-and-back or a loop does not jump to the other leg where both pass close by. Only where nothing in
 *   that window is close does it look further: first ahead (a shortcut), then anywhere.
 * - The workout joins the course once it first comes within [ON_COURSE_M] of it, at the earliest part of the
 *   course that close; until then it is heading to the course, not off it.
 * - Off course takes [STRAY_FIXES] positions in a row more than [OFF_COURSE_M] away; back on course, one within
 *   [ON_COURSE_M]. The gap between keeps one stray position from flapping the alert.
 * - The course is finished within [FINISH_M] of its end once [FINISH_SHARE] of it is behind.
 */
class CourseNavigator(private val course: Course) {
    private val points = course.points
    private val cumulative = CourseGeometry.cumulative(points)
    private val total = cumulative.lastOrNull() ?: 0.0

    private var joined = false
    private var segment = 0
    private var progress = 0.0
    private var onCourse = true
    private var strays = 0
    private var finished = false
    private var offCourse = 0.0

    /** Where the workout stands, before or after any position. */
    val status: NavigationStatus
        get() = NavigationStatus(
            courseId = course.id,
            progressMeters = progress.roundToInt(),
            remainingMeters = (total - progress).coerceAtLeast(0.0).roundToInt(),
            offCourseMeters = offCourse.roundToInt(),
            onCourse = onCourse,
            finished = finished,
            joined = joined,
        )

    /** Takes in a position with its horizontal [accuracyM]; too inaccurate a one changes nothing. */
    fun update(lat: Double, lon: Double, accuracyM: Double = 0.0): NavigationStatus {
        if (points.size < 2 || accuracyM > MAX_ACCURACY_M) return status
        val projections = List(points.size - 1) { CourseGeometry.project(lat, lon, points[it], points[it + 1]) }
        offCourse = projections.minOf { it.meters }

        val match = if (joined) rejoin(projections) else join(projections)
        if (match != null) {
            joined = true
            segment = match
            progress = cumulative[match] + projections[match].fraction * (cumulative[match + 1] - cumulative[match])
        }

        if (joined) {
            when {
                offCourse <= ON_COURSE_M -> {
                    strays = 0
                    onCourse = true
                }
                offCourse > OFF_COURSE_M -> {
                    strays++
                    if (strays >= STRAY_FIXES) onCourse = false
                }
                else -> strays = 0
            }
            val toEnd = CourseGeometry.distance(lat, lon, points.last().lat, points.last().lon)
            if (!finished && progress >= total * FINISH_SHARE && toEnd <= FINISH_M) finished = true
        }
        return status
    }

    /** The earliest segment the workout is close to, where it first meets the course. */
    private fun join(projections: List<CourseGeometry.Projection>): Int? =
        projections.indices.firstOrNull { projections[it].meters <= ON_COURSE_M }

    private fun rejoin(projections: List<CourseGeometry.Projection>): Int? {
        val window = projections.indices.filter { cumulative[it + 1] >= progress - BACK_M && cumulative[it] <= progress + AHEAD_M }
        val near = window.minByOrNull { projections[it].meters }
        if (near != null && projections[near].meters <= OFF_COURSE_M) return near
        // Rejoined elsewhere: a shortcut ahead first, else anywhere.
        return projections.indices.firstOrNull { it > segment && projections[it].meters <= ON_COURSE_M }
            ?: projections.indices.firstOrNull { projections[it].meters <= ON_COURSE_M }
    }

    companion object {
        const val ON_COURSE_M = 25.0
        const val OFF_COURSE_M = 40.0
        const val STRAY_FIXES = 2
        const val BACK_M = 50.0
        const val AHEAD_M = 300.0
        const val FINISH_M = 30.0
        const val FINISH_SHARE = 0.9

        /** Positions vaguer than this tell nothing about which side of a path the workout is on. */
        const val MAX_ACCURACY_M = 30.0
    }
}
