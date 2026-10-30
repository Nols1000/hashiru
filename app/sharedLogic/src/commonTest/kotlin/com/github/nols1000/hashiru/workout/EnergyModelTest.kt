package com.github.nols1000.hashiru.workout

import com.github.nols1000.hashiru.workout.domain.Activity
import com.github.nols1000.hashiru.workout.domain.Sex
import com.github.nols1000.hashiru.workout.domain.UserProfile
import com.github.nols1000.hashiru.workout.domain.EnergyModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EnergyModelTest {
    @Test
    fun oneKilometreInFifteenMinutesAtSeventyKilos() {
        assertEquals(35.0, EnergyModel.activeKcal(1_000, 70.0), 1e-9)
        assertEquals(53.375, EnergyModel.totalKcal(1_000, 900, 70.0), 1e-9)
    }

    @Test
    fun runningCostsTwiceTheNetOxygenPerMetre() {
        // ACSM running: net 0.2 mL/kg per metre; 5 km at 70 kg = 0.2 · 5000 · 70 / 200 kcal.
        assertEquals(350.0, EnergyModel.activeKcal(5_000, 70.0, Activity.Running), 1e-9)
        assertEquals(350.0 + 3.5 * 25 * 70 / 200, EnergyModel.totalKcal(5_000, 1_500, 70.0, Activity.Running), 1e-9)
    }

    @Test
    fun activeEnergyScalesWithDistanceAndWeight() {
        assertEquals(2 * EnergyModel.activeKcal(500, 80.0), EnergyModel.activeKcal(1_000, 80.0), 1e-9)
        assertEquals(2 * EnergyModel.activeKcal(1_000, 50.0), EnergyModel.activeKcal(1_000, 100.0), 1e-9)
        assertEquals(0.0, EnergyModel.activeKcal(0, 70.0))
    }

    @Test
    fun keytelForAFortyYearOldManAtSeventyKilos() {
        // (−55.0969 + 0.6309 · 110 + 0.1988 · 70 + 0.2017 · 40) kJ/min = 36.2861 kJ/min ≈ 8.6726 kcal/min.
        val estimate = assertNotNull(EnergyModel.heartRate(110, 600, 70.0, UserProfile(40, Sex.Male)))

        assertEquals(86.726, estimate.totalKcal, 1e-3)
        assertEquals(86.726 - 12.25, estimate.activeKcal, 1e-3)
    }

    @Test
    fun keytelForAFortyYearOldWomanAtSixtyKilos() {
        // (−20.4022 + 0.4472 · 110 − 0.1263 · 60 + 0.074 · 40) kJ/min = 24.1718 kJ/min ≈ 5.7772 kcal/min.
        val estimate = assertNotNull(EnergyModel.heartRate(110, 60, 60.0, UserProfile(40, Sex.Female)))

        assertEquals(5.7772, estimate.totalKcal, 1e-3)
    }

    @Test
    fun keytelNeedsAgeSexAndAnExerciseHeartRate() {
        assertNull(EnergyModel.heartRate(110, 60, 70.0, UserProfile(sex = Sex.Male)))
        assertNull(EnergyModel.heartRate(110, 60, 70.0, UserProfile(ageYears = 40)))
        assertNull(EnergyModel.heartRate(85, 60, 70.0, UserProfile(40, Sex.Male)))
    }

    @Test
    fun keytelNeverGoesBelowResting() {
        val estimate = assertNotNull(EnergyModel.heartRate(90, 60, 120.0, UserProfile(20, Sex.Female)))

        assertEquals(0.0, estimate.activeKcal, 1e-9)
        assertEquals(3.5 * 120.0 / 200, estimate.totalKcal, 1e-9)
    }
}
