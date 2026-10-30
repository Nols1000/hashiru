package com.github.nols1000.hashiru.course

import com.github.nols1000.hashiru.course.domain.CourseGeometry
import com.github.nols1000.hashiru.course.domain.CoursePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourseGeometryTest {
    @Test
    fun aDegreeOfLatitudeIsAboutOneHundredElevenKilometres() {
        assertEquals(111_195.0, CourseGeometry.distance(0.0, 0.0, 1.0, 0.0), 10.0)
    }

    @Test
    fun simplifyingKeepsTheCornerAndDropsTheStraight() {
        // East along a street, then north: 1e-4 degrees is about 11 m.
        val east = (0..10).map { CoursePoint(0.0, it * 1e-4) }
        val north = (1..10).map { CoursePoint(it * 1e-4, 10e-4) }
        val wobble = east.mapIndexed { i, p -> if (i == 5) p.copy(lat = 1e-5) else p }

        assertEquals(
            listOf(CoursePoint(0.0, 0.0), CoursePoint(0.0, 10e-4), CoursePoint(10e-4, 10e-4)),
            CourseGeometry.simplify(wobble + north, 3.0),
        )
    }

    @Test
    fun simplifyingToAPointBudgetGrowsTheTolerance() {
        val zigzag = (0..1_000).map { CoursePoint(if (it % 2 == 0) 0.0 else 1e-4, it * 1e-4) }
        assertTrue(CourseGeometry.simplify(zigzag, 1.0, maxPoints = 50).size <= 50)
    }

    @Test
    fun ascentCountsClimbsAndIgnoresNoise() {
        val points = listOf(100.0, 101.0, 100.0, 102.0, 105.0, 104.0, 110.0, 90.0, 95.0).map { CoursePoint(0.0, 0.0, it) }
        // 100 → 105, 104 → 110, 90 → 95; the metre-sized wobbles at the start do not count.
        assertEquals(16.0, CourseGeometry.ascent(points)!!, 1e-9)
        assertNull(CourseGeometry.ascent(listOf(CoursePoint(0.0, 0.0), CoursePoint(1.0, 1.0))))
    }
}
