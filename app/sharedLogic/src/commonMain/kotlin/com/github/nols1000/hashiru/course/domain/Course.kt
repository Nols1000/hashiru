package com.github.nols1000.hashiru.course.domain

/** A position on a planned course; [ele] in metres where the file had it. */
data class CoursePoint(val lat: Double, val lon: Double, val ele: Double? = null)

/**
 * A route to follow, imported from a GPX or FIT file and simplified on the way in. [distanceMeters] and
 * [ascentMeters] describe the course as stored; [importedAtMs] is epoch milliseconds.
 */
data class Course(
    val id: String,
    val name: String,
    val points: List<CoursePoint>,
    val distanceMeters: Int,
    /** Null when the file had no elevation. */
    val ascentMeters: Int?,
    val importedAtMs: Long,
) {
    /** True when the finish lies close to the start, so the course comes back round. */
    val isLoop: Boolean
        get() = points.size > 2 && CourseGeometry.distance(points.first(), points.last()) <= CourseGeometry.LOOP_METERS
}

/** Where a workout stands on its course. All values are metres along or off the course. */
data class NavigationStatus(
    val courseId: String,
    val progressMeters: Int,
    val remainingMeters: Int,
    /** How far the last position lay from the course. */
    val offCourseMeters: Int,
    /** False once the workout has strayed from the course, until it comes back. */
    val onCourse: Boolean,
    /** The workout reached the end of the course. */
    val finished: Boolean,
    /** False while heading to the course, before first reaching it; [offCourseMeters] is then the way there. */
    val joined: Boolean,
)

enum class ImportFailure {
    /** The file was read, but holds no positions. */
    Empty,

    /** Neither a GPX nor a FIT file. */
    Unrecognized,

    /** A FIT file whose checksum or structure is broken. */
    Corrupt,
}

sealed interface ImportResult {
    data class Imported(val course: Course) : ImportResult
    data class Failed(val reason: ImportFailure) : ImportResult
}

/** What a parser finds in a file, before simplification. */
data class ParsedCourse(val name: String?, val points: List<CoursePoint>)
