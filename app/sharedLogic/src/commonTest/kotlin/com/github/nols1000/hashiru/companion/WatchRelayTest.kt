package com.github.nols1000.hashiru.companion

import com.github.nols1000.hashiru.companion.adapter.MockWatchCompanionAdapter
import com.github.nols1000.hashiru.companion.domain.CompanionState
import com.github.nols1000.hashiru.companion.domain.WristCommand
import com.github.nols1000.hashiru.companion.engine.WatchRelay
import com.github.nols1000.hashiru.course.adapter.InMemoryCourseLibrary
import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.domain.CoursePoint
import com.github.nols1000.hashiru.treadmill.adapter.MockAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.MockTransportAdapter
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.workout.adapter.InMemoryKeyValueStore
import com.github.nols1000.hashiru.workout.adapter.MockHealthSyncAdapter
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
import com.github.nols1000.hashiru.workout.store.WorkoutStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchRelayTest {
    private val watch = MockWatchCompanionAdapter(simulateHeartRate = false)

    private class Rig(val session: TreadmillSession, val workouts: WorkoutRecorder, val relay: WatchRelay)

    private val course = Course("course-1", "East", listOf(CoursePoint(0.0, 0.0), CoursePoint(0.0, 0.01)), 1_112, null, 0)

    private suspend fun TestScope.rig(): Rig {
        val transport = MockTransportAdapter(30, 10, testScheduler.timeSource)
        val session = TreadmillSession(transport, MockAssociationAdapter(), backgroundScope, testScheduler.timeSource)
        val workouts = WorkoutRecorder(
            session, MockHealthSyncAdapter(), WorkoutStore(InMemoryKeyValueStore()), backgroundScope, { testScheduler.currentTime }, { "walk" },
            InMemoryCourseLibrary(listOf(course)),
        )
        val relay = WatchRelay(session, workouts, watch, backgroundScope)
        relay.start()
        session.connect()
        runCurrent()
        return Rig(session, workouts, relay)
    }

    /** Starts the belt from the wrist and walks until the pad reports 3 km/h. */
    private fun TestScope.startFromWrist() {
        watch.send(WristCommand.SetPaused(false))
        advanceTimeBy(3_000)
    }

    @Test
    fun aWalkStartedFromTheWristAsksTheWatchToStartItsSession() = runTest {
        val rig = rig()
        startFromWrist()

        assertEquals(WorkoutPhase.Active, rig.workouts.status.value.phase)
        assertEquals(listOf<Pair<WristCommand, Boolean>>(WristCommand.SetPaused(false) to true), watch.replies)
        assertEquals(1, watch.startRequests)
        assertEquals(WorkoutPhase.Active, watch.pushedStates.last().phase)
    }

    @Test
    fun theCourseGoesToTheWatchForTheWorkoutAndIsTakenBackAfter() = runTest {
        val rig = rig()
        rig.workouts.start(WorkoutKind.OutdoorRun, course.id)
        runCurrent()

        assertEquals(course, watch.pushedCourses.last())
        assertEquals(1_112, watch.pushedStates.last().navigation?.remainingMeters)

        rig.workouts.discard()
        runCurrent()
        assertNull(watch.pushedCourses.last())
    }

    @Test
    fun telemetryGoesOutAtMostOncePerSecond() = runTest {
        rig()
        startFromWrist()
        val before = watch.pushedTelemetry.size
        advanceTimeBy(10_000)

        val sent = watch.pushedTelemetry.size - before
        assertTrue(sent in 9..11, "sent $sent")
        assertEquals(30, watch.pushedTelemetry.last().speedTenths)
    }

    @Test
    fun noTelemetryWhileTheWatchIsUnreachable() = runTest {
        rig()
        watch.state.value = CompanionState.Unreachable
        startFromWrist()
        advanceTimeBy(5_000)

        assertTrue(watch.pushedTelemetry.isEmpty())
        assertEquals(WorkoutPhase.Active, watch.pushedStates.last().phase)
    }

    @Test
    fun stopBeltStopsThePadAndThePhasePauses() = runTest {
        val rig = rig()
        startFromWrist()

        watch.send(WristCommand.StopBelt)
        advanceTimeBy(2_000)

        assertEquals<Pair<WristCommand, Boolean>>(WristCommand.StopBelt to true, watch.replies.last())
        assertEquals(false, rig.session.telemetry.value.isRunning)
        assertEquals(WorkoutPhase.Paused, rig.workouts.status.value.phase)
    }

    @Test
    fun speedFromTheWristIsClampedToThePadsRange() = runTest {
        val rig = rig()
        startFromWrist()

        watch.send(WristCommand.SetSpeed(99))
        advanceTimeBy(5_000)

        assertEquals(6.0, rig.session.telemetry.value.speedKmh)
    }

    @Test
    fun speedIsRefusedWhileTheBeltIsStopped() = runTest {
        rig()
        watch.send(WristCommand.SetSpeed(40))
        runCurrent()

        assertEquals<Pair<WristCommand, Boolean>>(WristCommand.SetSpeed(40) to false, watch.replies.single())
    }

    @Test
    fun heartRateFeedsTheWalkAndGoesStale() = runTest {
        val rig = rig()
        startFromWrist()

        watch.measure(HeartRateSample(testScheduler.currentTime, 250))
        runCurrent()
        assertNull(rig.relay.heartRate.value)

        watch.measure(HeartRateSample(testScheduler.currentTime, 104))
        advanceTimeBy(1_500)
        assertEquals(104, rig.relay.heartRate.value?.bpm)
        assertEquals(104, rig.relay.telemetry.value.heartRateBpm)

        advanceTimeBy(WatchRelay.STALE_MS)
        assertNull(rig.relay.heartRate.value)
    }
}
