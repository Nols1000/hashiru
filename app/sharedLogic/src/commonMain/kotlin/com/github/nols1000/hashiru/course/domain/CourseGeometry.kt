package com.github.nols1000.hashiru.course.domain

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distances on the Earth for courses of a few kilometres to a few hundred: haversine between points, and a local
 * flat projection around each segment for the distance to it, which is accurate to well under a metre at that size.
 */
object CourseGeometry {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** A finish this close to the start makes a loop. */
    const val LOOP_METERS = 50.0

    /** Rises smaller than this are GPS and barometer noise, not climbing. */
    const val ASCENT_THRESHOLD_M = 3.0

    fun distance(a: CoursePoint, b: CoursePoint): Double = distance(a.lat, a.lon, b.lat, b.lon)

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1).rad()
        val dLon = (lon2 - lon1).rad()
        val h = sin(dLat / 2).let { it * it } + cos(lat1.rad()) * cos(lat2.rad()) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Where [p] lies against the segment [a]–[b]: the distance to it and how far along it (0 to 1). */
    data class Projection(val meters: Double, val fraction: Double)

    fun project(lat: Double, lon: Double, a: CoursePoint, b: CoursePoint): Projection {
        // Metres east and north of a.
        val cosLat = cos(a.lat.rad())
        fun x(l: Double) = (l - a.lon).rad() * EARTH_RADIUS_M * cosLat
        fun y(l: Double) = (l - a.lat).rad() * EARTH_RADIUS_M
        val bx = x(b.lon)
        val by = y(b.lat)
        val px = x(lon)
        val py = y(lat)
        val length2 = bx * bx + by * by
        val t = if (length2 == 0.0) 0.0 else ((px * bx + py * by) / length2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return Projection(sqrt(dx * dx + dy * dy), t)
    }

    /** Distance from the start to each point, in metres. */
    fun cumulative(points: List<CoursePoint>): DoubleArray {
        val result = DoubleArray(points.size)
        for (i in 1 until points.size) result[i] = result[i - 1] + distance(points[i - 1], points[i])
        return result
    }

    /** Total climb, counting only rises of at least [ASCENT_THRESHOLD_M]; null without elevation. */
    fun ascent(points: List<CoursePoint>): Double? {
        val elevations = points.mapNotNull { it.ele }
        if (elevations.size < 2) return null
        var total = 0.0
        // The elevation climbs are measured from: it follows every descent, and moves up once a rise counts.
        var reference = elevations.first()
        for (e in elevations.drop(1)) {
            if (e - reference >= ASCENT_THRESHOLD_M) {
                total += e - reference
                reference = e
            } else if (e < reference) {
                reference = e
            }
        }
        return total
    }

    /** Ramer–Douglas–Peucker: drops points that lie within [toleranceM] of the line kept around them. */
    fun simplify(points: List<CoursePoint>, toleranceM: Double): List<CoursePoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        // An explicit stack, as a long track would overflow recursion.
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var farthest = -1
            var farthestM = toleranceM
            for (i in first + 1 until last) {
                val d = project(points[i].lat, points[i].lon, points[first], points[last]).meters
                if (d > farthestM) {
                    farthest = i
                    farthestM = d
                }
            }
            if (farthest >= 0) {
                keep[farthest] = true
                stack.addLast(first to farthest)
                stack.addLast(farthest to last)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Simplifies with growing tolerance until at most [maxPoints] remain, for small screens and slow links. */
    fun simplify(points: List<CoursePoint>, toleranceM: Double, maxPoints: Int): List<CoursePoint> {
        var tolerance = toleranceM
        var result = simplify(points, tolerance)
        while (result.size > maxPoints) {
            tolerance *= 2
            result = simplify(points, tolerance)
        }
        return result
    }

    private fun Double.rad() = this * PI / 180
}
