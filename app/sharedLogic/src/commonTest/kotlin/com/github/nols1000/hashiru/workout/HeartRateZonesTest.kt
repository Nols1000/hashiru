package com.github.nols1000.hashiru.workout

import com.github.nols1000.hashiru.workout.domain.HeartRateZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeartRateZonesTest {
    @Test
    fun zonesFollowTheTanakaMaximum() {
        // 208 − 0.7 · 40 = 180 bpm.
        assertEquals(180.0, HeartRateZone.maxHeartRate(40), 1e-9)
        assertEquals(HeartRateZone.Warmup, HeartRateZone.of(80, 40))
        assertEquals(HeartRateZone.Easy, HeartRateZone.of(108, 40))
        assertEquals(HeartRateZone.Aerobic, HeartRateZone.of(126, 40))
        assertEquals(HeartRateZone.Threshold, HeartRateZone.of(144, 40))
        assertEquals(HeartRateZone.Maximum, HeartRateZone.of(162, 40))
    }

    @Test
    fun noZoneWithoutAnAge() {
        assertNull(HeartRateZone.of(120, null))
    }
}
