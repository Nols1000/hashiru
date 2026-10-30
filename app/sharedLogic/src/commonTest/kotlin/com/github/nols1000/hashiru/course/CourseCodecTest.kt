package com.github.nols1000.hashiru.course

import com.github.nols1000.hashiru.course.codec.CourseCodec
import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.domain.CoursePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourseCodecTest {
    private val course = Course(
        id = "c-1",
        name = "Park | loop; west, side",
        points = listOf(CoursePoint(35.685201, 139.710101, 40.2), CoursePoint(-33.8688, -0.000001, null)),
        distanceMeters = 5_120,
        ascentMeters = 86,
        importedAtMs = 1_700_000_000_000,
    )

    @Test
    fun aStoredCourseRoundTrips() {
        assertEquals(course.copy(name = "Park  loop west side"), CourseCodec.decode(CourseCodec.encode(course)))
        assertEquals(course.copy(name = "x", ascentMeters = null), CourseCodec.decode(CourseCodec.encode(course.copy(name = "x", ascentMeters = null))))
    }

    @Test
    fun theWatchGetsAtMostItsShareOfPoints() {
        val long = course.copy(points = (0..5_000).map { CoursePoint(if (it % 2 == 0) 0.0 else 1e-3, it * 1e-4) })
        val text = CourseCodec.encodeForWatch(long)
        val decoded = CourseCodec.decodeForWatch(text)!!

        assertTrue(decoded.points.size <= CourseCodec.WATCH_MAX_POINTS)
        assertEquals("c-1", decoded.id)
        assertEquals(5_120, decoded.distanceMeters)
        assertTrue(text.startsWith("R|1|c-1|Park  loop west side|5120|0.00000,0.00000;"))
    }

    @Test
    fun otherTextDecodesToNull() {
        assertNull(CourseCodec.decode("garbage"))
        assertNull(CourseCodec.decodeForWatch("R|2|x"))
    }
}
