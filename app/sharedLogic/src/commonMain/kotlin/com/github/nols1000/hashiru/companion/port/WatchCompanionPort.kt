package com.github.nols1000.hashiru.companion.port

import com.github.nols1000.hashiru.companion.domain.CompanionState
import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.companion.domain.IncomingCommand
import com.github.nols1000.hashiru.companion.domain.WristTelemetry
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Driven port: the link to the watch app (WatchConnectivity on iOS, the Wear OS Data Layer on Android). */
interface WatchCompanionPort {
    val state: StateFlow<CompanionState>
    val heartRate: Flow<HeartRateSample>
    val commands: Flow<IncomingCommand>

    /** Sends live metrics over the fast path; may be dropped when the watch is not reachable. */
    suspend fun pushTelemetry(telemetry: WristTelemetry)

    /** Sends a state change over the durable path, which the watch gets once it is reachable again. */
    suspend fun pushState(telemetry: WristTelemetry)

    /** Sends the course the workout follows, or null when none, over the durable path; the watch draws it. */
    suspend fun pushCourse(course: Course?)

    /** Asks the watch app to start its workout session, so its heart-rate sensor runs. */
    suspend fun requestCompanionStart()
}
