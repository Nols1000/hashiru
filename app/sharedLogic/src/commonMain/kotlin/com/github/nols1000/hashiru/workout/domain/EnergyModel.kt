package com.github.nols1000.hashiru.workout.domain

data class EnergyEstimate(val activeKcal: Double, val totalKcal: Double)

/**
 * Energy estimates from the ACSM level equations, with 5 kcal per litre of O₂ (VO₂ in mL/kg/min, v in m/min):
 * walking, VO₂ = 3.5 + 0.1 · v, meant for 50–100 m/min (3–6 km/h); running, VO₂ = 3.5 + 0.2 · v, meant for
 * more than 134 m/min (8 km/h).
 *
 * On level ground the net (active) part, k · v, integrates to k · distance, so active energy depends on
 * distance only. Total energy adds the 3.5 mL/kg/min resting part (1 MET) over the active time.
 *
 * With heart rate, age and sex known, [heartRate] uses Keytel et al. (2005) instead, which follows the
 * walker's actual effort, such as on a heavier day or with a load.
 */
object EnergyModel {
    private const val WALKING_NET_O2_PER_METER = 0.1
    private const val RUNNING_NET_O2_PER_METER = 0.2
    private const val RESTING_O2_PER_MINUTE = 3.5
    private const val KCAL_PER_ML_O2_PER_KG = 1.0 / 200
    private const val KJ_PER_KCAL = 4.184

    /** Keytel was fitted on exercise heart rates; below this it gives nonsense. */
    const val MIN_HEART_RATE_BPM = 90

    fun activeKcal(distanceMeters: Int, weightKg: Double, activity: Activity = Activity.Walking): Double =
        netO2PerMeter(activity) * distanceMeters * weightKg * KCAL_PER_ML_O2_PER_KG

    fun totalKcal(distanceMeters: Int, activeSeconds: Int, weightKg: Double, activity: Activity = Activity.Walking): Double =
        activeKcal(distanceMeters, weightKg, activity) + restingKcal(activeSeconds, weightKg)

    /**
     * Gross energy from Keytel's heart-rate equation over [seconds], split into active and resting parts with
     * the same 1 MET resting rate as the walking equation. Null when age or sex is unknown or [avgBpm] is
     * below [MIN_HEART_RATE_BPM].
     */
    fun heartRate(avgBpm: Int, seconds: Int, weightKg: Double, profile: UserProfile): EnergyEstimate? {
        val age = profile.ageYears ?: return null
        val sex = profile.sex ?: return null
        if (avgBpm < MIN_HEART_RATE_BPM) return null
        val kjPerMinute = when (sex) {
            Sex.Male -> -55.0969 + 0.6309 * avgBpm + 0.1988 * weightKg + 0.2017 * age
            Sex.Female -> -20.4022 + 0.4472 * avgBpm - 0.1263 * weightKg + 0.074 * age
        }
        val resting = restingKcal(seconds, weightKg)
        val total = maxOf(kjPerMinute / KJ_PER_KCAL * seconds / 60.0, resting)
        return EnergyEstimate(total - resting, total)
    }

    private fun netO2PerMeter(activity: Activity) = when (activity) {
        Activity.Walking -> WALKING_NET_O2_PER_METER
        Activity.Running -> RUNNING_NET_O2_PER_METER
    }

    private fun restingKcal(seconds: Int, weightKg: Double) = RESTING_O2_PER_MINUTE * (seconds / 60.0) * weightKg * KCAL_PER_ML_O2_PER_KG
}
