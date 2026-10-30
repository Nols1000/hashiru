package com.github.nols1000.hashiru.course

import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.domain.CourseGeometry
import com.github.nols1000.hashiru.course.domain.CoursePoint
import com.github.nols1000.hashiru.course.engine.CourseNavigator
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CourseNavigatorTest {
    /** About 11.1 m per step of 1e-4 degrees at the equator. */
    private val step = 1e-4
    private val metresPerStep = CourseGeometry.distance(0.0, 0.0, 0.0, step)

    private fun course(points: List<CoursePoint>) =
        Course("c", "Test", points, CourseGeometry.cumulative(points).last().roundToInt(), null, 0)

    /** 1.1 km due east along the equator. */
    private val straight = course(listOf(CoursePoint(0.0, 0.0), CoursePoint(0.0, 100 * step)))

    @Test
    fun progressFollowsTheWorkoutAlongTheCourse() {
        val navigator = CourseNavigator(straight)
        val status = navigator.update(0.00005, 30 * step)

        assertTrue(status.joined)
        assertTrue(status.onCourse)
        assertEquals((30 * metresPerStep).roundToInt(), status.progressMeters)
        assertEquals((70 * metresPerStep).roundToInt(), status.remainingMeters)
        assertEquals(6, status.offCourseMeters)
    }

    @Test
    fun headingToTheCourseIsNotStraying() {
        val navigator = CourseNavigator(straight)
        val status = navigator.update(10 * step, 0.0)

        assertFalse(status.joined)
        assertTrue(status.onCourse)
        assertEquals(0, status.progressMeters)
        assertEquals((10 * metresPerStep).roundToInt(), status.offCourseMeters)
    }

    @Test
    fun strayingTakesTwoFarPositionsAndOneNearOneEndsIt() {
        val navigator = CourseNavigator(straight)
        navigator.update(0.0, 10 * step)

        assertTrue(navigator.update(5 * step, 12 * step).onCourse)
        assertFalse(navigator.update(5 * step, 13 * step).onCourse)
        // Between the thresholds: still off.
        assertFalse(navigator.update(3 * step, 14 * step).onCourse)
        assertTrue(navigator.update(step, 15 * step).onCourse)
    }

    @Test
    fun anOutAndBackDoesNotJumpToTheWayBack() {
        // East 550 m and back again, 10 m to the north.
        val outAndBack = course(
            listOf(CoursePoint(0.0, 0.0), CoursePoint(0.0, 50 * step), CoursePoint(step, 50 * step), CoursePoint(step, 0.0)),
        )
        val navigator = CourseNavigator(outAndBack)
        for (s in 0..20) navigator.update(0.0, s * step)

        val status = navigator.update(step / 2, 21 * step)
        assertEquals((21 * metresPerStep).roundToInt(), status.progressMeters, "still on the way out")
    }

    @Test
    fun aLoopIsOnlyFinishedAtTheEnd() {
        val loop = course(
            listOf(CoursePoint(0.0, 0.0), CoursePoint(0.0, 30 * step), CoursePoint(30 * step, 30 * step), CoursePoint(30 * step, 0.0), CoursePoint(0.0, 0.0)),
        )
        assertTrue(loop.isLoop)
        val navigator = CourseNavigator(loop)
        assertFalse(navigator.update(0.0, 0.0).finished)
        for (s in 0..30) navigator.update(0.0, s * step)
        for (s in 0..30) navigator.update(s * step, 30 * step)
        for (s in 30 downTo 0) navigator.update(30 * step, s * step)
        for (s in 30 downTo 0) navigator.update(s * step, 0.0)

        assertTrue(navigator.status.finished)
        assertEquals(0, navigator.status.remainingMeters)
    }

    @Test
    fun aShortcutRejoinsAhead() {
        val navigator = CourseNavigator(straight)
        navigator.update(0.0, 10 * step)
        navigator.update(5 * step, 30 * step)
        navigator.update(5 * step, 50 * step)

        val back = navigator.update(0.0, 80 * step)
        assertTrue(back.onCourse)
        assertEquals((80 * metresPerStep).roundToInt(), back.progressMeters)
    }

    @Test
    fun vaguePositionsChangeNothing() {
        val navigator = CourseNavigator(straight)
        navigator.update(0.0, 10 * step)
        val status = navigator.update(10 * step, 20 * step, accuracyM = 65.0)

        assertEquals((10 * metresPerStep).roundToInt(), status.progressMeters)
        assertTrue(status.onCourse)
    }
}
