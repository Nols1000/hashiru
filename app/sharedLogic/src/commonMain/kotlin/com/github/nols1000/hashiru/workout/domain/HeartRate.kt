package com.github.nols1000.hashiru.workout.domain

/** One optical heart-rate reading from the watch. [epochMs] is the watch's measurement time. */
data class HeartRateSample(val epochMs: Long, val bpm: Int)

/** Heart rate over one window. [series] is thinned to one reading per [HeartRateSummary.SERIES_INTERVAL_MS]. */
data class HeartRateSummary(
    val avgBpm: Int,
    val minBpm: Int,
    val maxBpm: Int,
    /** True when the window's energy came from heart rate rather than the walking equation. */
    val usedForEnergy: Boolean,
    val series: List<HeartRateSample>,
) {
    companion object {
        const val SERIES_INTERVAL_MS = 5_000L
    }
}

/**
 * Zones as fractions of the age-predicted maximum (Tanaka: 208 − 0.7 · age). Everything below 60 % counts
 * as warm-up, since a walk rarely goes lower than 50 % for long.
 */
enum class HeartRateZone {
    Warmup,
    Easy,
    Aerobic,
    Threshold,
    Maximum,
    ;

    companion object {
        fun maxHeartRate(ageYears: Int): Double = 208 - 0.7 * ageYears

        /** The zone of [bpm], or null when the age, and with it the maximum, is unknown. */
        fun of(bpm: Int, ageYears: Int?): HeartRateZone? {
            val fraction = bpm / maxHeartRate(ageYears ?: return null)
            return when {
                fraction < 0.6 -> Warmup
                fraction < 0.7 -> Easy
                fraction < 0.8 -> Aerobic
                fraction < 0.9 -> Threshold
                else -> Maximum
            }
        }
    }
}
