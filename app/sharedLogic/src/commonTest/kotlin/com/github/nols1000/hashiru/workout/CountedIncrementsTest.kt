package com.github.nols1000.hashiru.workout

import com.github.nols1000.hashiru.workout.adapter.CountedIncrements
import com.github.nols1000.hashiru.workout.adapter.Increment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CountedIncrementsTest {
    @Test
    fun eachIncrementCoversTheTimeSinceTheLast() {
        val counts = CountedIncrements(1_000)

        assertEquals(Increment(1_000, 4_000, 3, 5), counts.next(4_000, 3, 5))
        assertEquals(Increment(4_000, 7_000, 2, 4), counts.next(7_000, 5, 9))
    }

    @Test
    fun aLostCountOnlyWidensTheNextIncrement() {
        val counts = CountedIncrements(0)
        counts.next(3_000, 2, 4)
        // The count at 6 s never arrived.

        assertEquals(Increment(3_000, 9_000, 6, 10), counts.next(9_000, 8, 14))
    }

    @Test
    fun aRepeatedOrLateCountAddsNothing() {
        val counts = CountedIncrements(0)
        counts.next(3_000, 2, 4)

        assertNull(counts.next(3_000, 2, 4))
        assertNull(counts.next(2_000, 1, 2))
        assertNull(counts.next(5_000, 2, 4))
    }

    @Test
    fun aRecoveredSessionGoesOnFromWhatItHolds() {
        val counts = CountedIncrements(0)
        counts.seed(meters = 120, steps = 200, atMs = 60_000)

        assertEquals(Increment(60_000, 63_000, 3, 5), counts.next(63_000, 123, 205))
    }
}
