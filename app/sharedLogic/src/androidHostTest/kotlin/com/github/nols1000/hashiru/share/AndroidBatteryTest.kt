package com.github.nols1000.hashiru.share

import com.github.nols1000.hashiru.share.adapter.batteryPercent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidBatteryTest {
    @Test
    fun scalesTheLevel() {
        assertEquals(50, batteryPercent(level = 50, scale = 100))
        assertEquals(75, batteryPercent(level = 3, scale = 4))
        assertEquals(100, batteryPercent(level = 255, scale = 255))
    }

    @Test
    fun knowsNothingWithoutLevelOrScale() {
        assertNull(batteryPercent(level = -1, scale = 100))
        assertNull(batteryPercent(level = 50, scale = 0))
    }
}
