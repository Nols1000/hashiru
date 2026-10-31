package com.github.nols1000.hashiru.workout

import com.github.nols1000.hashiru.workout.adapter.InMemoryKeyValueStore
import com.github.nols1000.hashiru.workout.codec.WorkoutCodec
import com.github.nols1000.hashiru.workout.domain.BodyWeight
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.domain.WorkoutSource
import com.github.nols1000.hashiru.workout.engine.WorkoutState
import com.github.nols1000.hashiru.workout.port.KeyValueStorePort
import com.github.nols1000.hashiru.workout.store.WorkoutStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutStoreTest {
    /** Remembers every value written, so a test can tell how much each checkpoint costs. */
    private class RecordingStore : KeyValueStorePort {
        val values = InMemoryKeyValueStore()
        val keys = mutableSetOf<String>()
        val writes = mutableListOf<Pair<String, String?>>()

        override fun getString(key: String) = values.getString(key)

        override fun putString(key: String, value: String?) {
            writes += key to value
            if (value != null) keys += key
            values.putString(key, value)
        }

        fun live() = keys.filter { values.getString(it) != null }.toSet()
    }

    private val kv = RecordingStore()

    private fun point(i: Int) = RoutePoint(1_000L * i, 48.1 + i * 1e-5, 11.5 + i * 1e-5, if (i % 2 == 0) 520.0 else null, 4.5)

    private fun walk(points: Int, seconds: Int = points) = WorkoutState.InProgress(
        id = "walk", startMs = 0, weight = BodyWeight.Default,
        closed = PadCounters(seconds, seconds * 2, 0), segmentBase = PadCounters(0, 0, 0), segmentLast = PadCounters(0, 0, 0),
        windowStartMs = 0, windowStartTotals = PadCounters(0, 0, 0), samples = emptyList(), pauses = emptyList(), pause = null,
        kind = WorkoutKind.OutdoorRun, source = WorkoutSource.Phone, route = (0 until points).map(::point),
    )

    @Test
    fun eachCheckpointWritesOnlyTheNewPoints() {
        val store = WorkoutStore(kv)
        store.inProgress = walk(3_600)
        for (points in listOf(3_660, 3_720, 3_780)) {
            kv.writes.clear()
            store.inProgress = walk(points)

            val routeText = kv.writes.filter { it.first != "workout.inProgress" }.sumOf { it.second?.length ?: 0 }
            assertTrue(routeText < WorkoutCodec.encodeRoute((0 until 120).map(::point)).length, "wrote $routeText characters of route")
            assertTrue(kv.writes.single { it.first == "workout.inProgress" }.second!!.length < 500)
        }
        // A checkpoint without new points leaves the route alone.
        kv.writes.clear()
        store.inProgress = walk(3_780, seconds = 4_000)
        assertEquals(listOf("workout.inProgress"), kv.writes.map { it.first })
    }

    @Test
    fun aRestoreAfterProcessDeathGivesBackTheExactWalk() {
        val store = WorkoutStore(kv)
        listOf(0, 1, 250, 251, 900).forEach { store.inProgress = walk(it) }

        assertEquals(walk(900), WorkoutStore(kv).inProgress)
    }

    @Test
    fun finishingOrDiscardingRemovesTheChunks() {
        val store = WorkoutStore(kv)
        listOf(10, 20, 30).forEach { store.inProgress = walk(it) }
        assertTrue(kv.live().size > 2)

        store.inProgress = null

        assertEquals(emptySet(), kv.live())
        assertNull(WorkoutStore(kv).inProgress)
    }

    @Test
    fun aNewWalkDoesNotInheritTheLastOnesRoute() {
        val store = WorkoutStore(kv)
        store.inProgress = walk(30)
        store.inProgress = walk(5).copy(id = "next")

        assertEquals(walk(5).copy(id = "next"), WorkoutStore(kv).inProgress)
    }

    @Test
    fun aCheckpointFromBeforeChunksStillRestores() {
        kv.putString("workout.inProgress", WorkoutCodec.encodeInProgress(walk(40)))

        val store = WorkoutStore(kv)
        assertEquals(walk(40), store.inProgress)

        // The next checkpoint moves the route out of the walk and keeps it whole.
        store.inProgress = walk(45)
        assertEquals(walk(45), WorkoutStore(kv).inProgress)
        assertEquals(walk(45).copy(route = emptyList()), WorkoutCodec.decodeInProgress(kv.getString("workout.inProgress")!!))
    }
}
