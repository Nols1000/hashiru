package com.github.nols1000.hashiru.course.codec

import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.domain.CourseGeometry
import com.github.nols1000.hashiru.course.domain.CoursePoint
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Flat text for courses, in the style of `WorkoutCodec`: fields split by `|`, points by `;` and a point's values
 * by `,`. Names are stripped of separators. Decoding returns null for anything it does not understand.
 *
 * ```
 * C|1|id|Park loop|1700000000000|5120|86|35.68520,139.71010,40.2;35.68530,139.71020,   stored course
 * R|1|id|Park loop|5120|35.68520,139.71010;35.68530,139.71020                          for the watch
 * ```
 *
 * The watchOS app decodes the `R` form itself (`WatchCodec.swift`); keep both in step with this example.
 */
object CourseCodec {
    private const val VERSION = "1"

    /** The watch draws the course on a small screen; this many points are plenty, and keep the message small. */
    const val WATCH_MAX_POINTS = 400
    private const val WATCH_TOLERANCE_M = 10.0

    fun encode(course: Course): String = listOf(
        "C", VERSION, course.id, clean(course.name), course.importedAtMs, course.distanceMeters, course.ascentMeters ?: "",
        course.points.joinToString(";") { "${fixed(it.lat, 6)},${fixed(it.lon, 6)},${it.ele?.let { e -> fixed(e, 1) } ?: ""}" },
    ).joinToString("|")

    fun decode(text: String): Course? = runCatching {
        val f = text.trim().split('|')
        if (f.size != 8 || f[0] != "C" || f[1] != VERSION) return null
        Course(
            id = f[2],
            name = f[3],
            importedAtMs = f[4].toLong(),
            distanceMeters = f[5].toInt(),
            ascentMeters = f[6].toIntOrNull(),
            points = points(f[7]).map { v -> CoursePoint(v[0].toDouble(), v[1].toDouble(), v.getOrNull(2)?.toDoubleOrNull()) },
        )
    }.getOrNull()

    /** The course for the watch, simplified to at most [WATCH_MAX_POINTS] points. */
    fun encodeForWatch(course: Course): String {
        val points = CourseGeometry.simplify(course.points, WATCH_TOLERANCE_M, WATCH_MAX_POINTS)
        return listOf(
            "R", VERSION, course.id, clean(course.name), course.distanceMeters,
            points.joinToString(";") { "${fixed(it.lat, 5)},${fixed(it.lon, 5)}" },
        ).joinToString("|")
    }

    /** The watch's form read back, as the watch reads it; for tests and for keeping both sides in step. */
    fun decodeForWatch(text: String): Course? = runCatching {
        val f = text.split('|')
        if (f.size != 6 || f[0] != "R" || f[1] != VERSION) return null
        val points = points(f[5]).map { v -> CoursePoint(v[0].toDouble(), v[1].toDouble()) }
        Course(f[2], f[3], points, f[4].toInt(), ascentMeters = null, importedAtMs = 0)
    }.getOrNull()

    private fun points(text: String) = if (text.isEmpty()) emptyList() else text.split(';').map { it.split(',') }

    /** [value] with [decimals] places, without relying on platform number formatting. */
    private fun fixed(value: Double, decimals: Int): String {
        val scale = 10.0.pow(decimals)
        val scaled = (value * scale).roundToLong()
        val sign = if (scaled < 0) "-" else ""
        val digits = kotlin.math.abs(scaled).toString().padStart(decimals + 1, '0')
        return "$sign${digits.dropLast(decimals)}.${digits.takeLast(decimals)}"
    }

    private fun clean(name: String) = name.filterNot { it in "|;,\n\r" }.trim()
}
