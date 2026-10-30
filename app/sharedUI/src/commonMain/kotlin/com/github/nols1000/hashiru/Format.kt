package com.github.nols1000.hashiru

import androidx.compose.runtime.Composable
import kotlin.math.roundToInt

/** Date and time span of a walk in the user's locale, such as "Wed, 29 Oct, 18:02–18:35". */
@Composable
internal expect fun formatTimeRange(startEpochMs: Long, endEpochMs: Long): String

internal fun formatSpeed(tenths: Int): String = "${tenths / 10}.${tenths % 10}"

internal fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}

internal fun formatKilometres(meters: Int): String =
    "${meters / 1000}.${(meters % 1000 / 10).toString().padStart(2, '0')}"

/** Minutes and seconds per kilometre, or a dash until there is a pace. */
internal fun formatPace(secondsPerKm: Int?): String =
    secondsPerKm?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: "–:––"

/** Whole kilos without a decimal, others with one. */
internal fun formatWeight(kg: Double): String {
    val tenths = (kg * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

/** Accepts a decimal comma as well as a point; null unless it is a plausible body weight. */
internal fun parseWeight(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..300.0 }
