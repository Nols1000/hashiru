package com.github.nols1000.hashiru.workout.adapter

import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.PastWalk
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.port.WorkoutHistoryPort

/**
 * Walk history kept in memory, newest first. [readAccessRequests] counts the permission sheets it was asked to show;
 * reads throw while [failReads] is set.
 */
class MockWorkoutHistoryAdapter(
    walks: List<PastWalk> = emptyList(),
    var heartRate: Map<String, List<HeartRateSample>> = emptyMap(),
    var routes: Map<String, List<RoutePoint>> = emptyMap(),
) : WorkoutHistoryPort {
    private val _walks = walks.sortedByDescending { it.endMs }.toMutableList()
    val walks: List<PastWalk> get() = _walks

    var readAccessRequests = 0
        private set

    var failReads = false

    override suspend fun walks(sinceMs: Long?, beforeMs: Long?, limit: Int): List<PastWalk> =
        if (failReads) throw IllegalStateException("Health store unavailable") else _walks.filter { (sinceMs == null || it.startMs >= sinceMs) && (beforeMs == null || it.endMs < beforeMs) }.take(limit)

    override suspend fun heartRate(walk: PastWalk): List<HeartRateSample> = heartRate[walk.id].orEmpty()

    override suspend fun route(walk: PastWalk): List<RoutePoint> = routes[walk.id].orEmpty()

    override suspend fun requestReadAccess() {
        readAccessRequests++
    }

    override suspend fun delete(walk: PastWalk) {
        _walks.removeAll { it.id == walk.id }
    }

    fun add(walk: PastWalk) {
        _walks.add(walk)
        _walks.sortByDescending { it.endMs }
    }
}
