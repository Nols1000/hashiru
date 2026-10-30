package com.github.nols1000.hashiru.course.codec

import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.domain.CourseGeometry
import com.github.nols1000.hashiru.course.domain.ImportFailure
import com.github.nols1000.hashiru.course.domain.ImportResult
import kotlin.math.roundToInt

/**
 * Turns a GPX or FIT file into a [Course]: by its extension where it has a known one, else by its content. The
 * course is simplified to within [TOLERANCE_M] of the file's line, which keeps every turn that matters on foot and
 * drops the thousands of points a recorded track has on a straight. Without a name in the file, the file's name
 * stands in.
 */
object CourseImporter {
    const val TOLERANCE_M = 3.0

    fun import(fileName: String, bytes: ByteArray, id: String, nowMs: Long): ImportResult {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val parsed = when {
            extension == "fit" || extension != "gpx" && FitParser.recognizes(bytes) -> when (val fit = FitParser.parse(bytes)) {
                is FitParser.Result.Parsed -> fit.course
                FitParser.Result.Corrupt -> return ImportResult.Failed(ImportFailure.Corrupt)
                FitParser.Result.NotFit -> return ImportResult.Failed(ImportFailure.Unrecognized)
            }
            else -> GpxParser.parse(bytes.decodeToString()) ?: return ImportResult.Failed(ImportFailure.Unrecognized)
        }
        // Repeated positions, as from a paused recording, add nothing.
        val points = parsed.points.filterIndexed { i, p -> i == 0 || p.lat != parsed.points[i - 1].lat || p.lon != parsed.points[i - 1].lon }
        if (points.size < 2) return ImportResult.Failed(ImportFailure.Empty)
        val simplified = CourseGeometry.simplify(points, TOLERANCE_M)
        return ImportResult.Imported(
            Course(
                id = id,
                name = parsed.name ?: fileName.substringBeforeLast('.').ifBlank { "Route" },
                points = simplified,
                distanceMeters = CourseGeometry.cumulative(simplified).last().roundToInt(),
                // From every point: simplifying on the map drops the bumps in between.
                ascentMeters = CourseGeometry.ascent(points)?.roundToInt(),
                importedAtMs = nowMs,
            ),
        )
    }
}
