package com.github.nols1000.hashiru.workout

import com.github.nols1000.hashiru.course.adapter.InMemoryCourseLibrary
import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.domain.CoursePoint
import com.github.nols1000.hashiru.treadmill.adapter.MockAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.MockTransportAdapter
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.workout.adapter.InMemoryKeyValueStore
import com.github.nols1000.hashiru.workout.adapter.MockHealthSyncAdapter
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.workout.domain.RejectReason
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.SyncState
import com.github.nols1000.hashiru.workout.domain.WeightSource
import com.github.nols1000.hashiru.workout.domain.TrackedTotals
import com.github.nols1000.hashiru.workout.domain.WorkoutEvent
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.domain.WorkoutSource
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
import com.github.nols1000.hashiru.workout.engine.WorkoutRules
import com.github.nols1000.hashiru.workout.port.HealthAuthorization
import com.github.nols1000.hashiru.workout.port.HealthStoreBusyException
import com.github.nols1000.hashiru.workout.store.HealthPrompt
import com.github.nols1000.hashiru.workout.store.WorkoutStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutRecorderTest {
    private lateinit var transport: MockTransportAdapter
    private val health = MockHealthSyncAdapter(authorization = HealthAuthorization.Granted)
    private val storage = InMemoryKeyValueStore()
    private val epoch = 1_700_000_000_000L

    /** 1.1 km due east along the equator. */
    private val course = Course("course-1", "East", listOf(CoursePoint(0.0, 0.0), CoursePoint(0.0, 0.01)), 1_112, null, 0)
    private val courses = InMemoryCourseLibrary(listOf(course))

    private fun TestScope.recorder(): Pair<TreadmillSession, WorkoutRecorder> {
        // The pad accumulates distance on the test clock, so virtual time walks it.
        transport = MockTransportAdapter(targetSpeedTenths = 30, rampStepTenths = 10, timeSource = testScheduler.timeSource)
        val session = TreadmillSession(transport, MockAssociationAdapter(), backgroundScope, testScheduler.timeSource)
        val recorder = WorkoutRecorder(
            session, health, WorkoutStore(storage), backgroundScope, { epoch + testScheduler.currentTime }, { "walk-1" }, courses,
        )
        session.start()
        recorder.start()
        runCurrent()
        return session to recorder
    }

    private suspend fun TestScope.walk(session: TreadmillSession, millis: Long) {
        session.connect()
        session.dispatch(TreadmillCommand.StartBelt)
        advanceTimeBy(millis)
    }

    @Test
    fun aFinishedWalkIsSavedToHealth() = runTest {
        val (session, recorder) = recorder()
        walk(session, 150_000)
        assertEquals(WorkoutPhase.Active, recorder.status.value.phase)

        recorder.end()
        // The walk ends once the belt reports stopped.
        advanceTimeBy(3_000)

        val saved = assertNotNull(health.saved["walk-1"])
        assertTrue(saved.distanceMeters >= WorkoutRules.MIN_DISTANCE_METERS)
        assertEquals(saved.distanceMeters, saved.samples.sumOf { it.distanceMeters })
        assertEquals(SyncState.Saved, recorder.status.value.summary?.syncState)
        assertEquals(WorkoutPhase.Idle, recorder.status.value.phase)
        assertFalse(session.telemetry.value.isRunning)
    }

    @Test
    fun aTooShortWalkIsNotSavedAndSaysSoUntilTheNextWalk() = runTest {
        val (session, recorder) = recorder()
        walk(session, 20_000)

        recorder.end()
        // The walk ends once the belt reports stopped.
        advanceTimeBy(3_000)

        assertTrue(health.saved.isEmpty())
        assertNull(recorder.status.value.summary)
        assertTrue(recorder.status.value.tooShort)

        session.dispatch(TreadmillCommand.StartBelt)
        advanceTimeBy(2_000)
        assertEquals(WorkoutPhase.Active, recorder.status.value.phase)
        assertFalse(recorder.status.value.tooShort)
    }

    @Test
    fun stoppingTheBeltPausesAndTheWalkEndsAfterTheTimeout() = runTest {
        val (session, recorder) = recorder()
        walk(session, 120_000)

        session.dispatch(TreadmillCommand.StopBelt)
        advanceTimeBy(2_000)
        assertEquals(PauseReason.BeltStopped, recorder.status.value.pauseReason)
        assertTrue(health.received.any { it is WorkoutEvent.Paused && it.id == "walk-1" })

        advanceTimeBy(WorkoutRules.PAUSE_TIMEOUT_MS)

        assertNotNull(health.saved["walk-1"])
        assertEquals(WorkoutPhase.Idle, recorder.status.value.phase)
    }

    @Test
    fun aPadWalkShowsTheHealthStoresTime() = runTest {
        val (session, recorder) = recorder()
        walk(session, 10_000)
        assertEquals(WorkoutPhase.Active, recorder.status.value.phase)

        health.progress("walk-1", TrackedTotals(42))
        runCurrent()

        assertEquals(42, recorder.status.value.totals.seconds)
    }

    @Test
    fun aFailedSaveIsRetried() = runTest {
        health.failNextSave = IllegalStateException("Store unavailable")
        val (session, recorder) = recorder()
        walk(session, 120_000)
        recorder.end()
        // The walk ends once the belt reports stopped.
        advanceTimeBy(3_000)
        assertEquals(SyncState.Failed("Store unavailable"), recorder.status.value.summary?.syncState)

        recorder.retryPendingSync()
        runCurrent()

        assertEquals(SyncState.Saved, recorder.status.value.summary?.syncState)
    }

    @Test
    fun aLockedStoreMarksTheWalkBusyUntilTheNextRetry() = runTest {
        health.failNextSave = HealthStoreBusyException("Protected data unavailable")
        val (session, recorder) = recorder()
        walk(session, 120_000)
        recorder.end()
        // The walk ends once the belt reports stopped.
        advanceTimeBy(3_000)

        assertEquals(SyncState.Busy, recorder.status.value.summary?.syncState)

        recorder.retryPendingSync()
        runCurrent()

        assertEquals(SyncState.Saved, recorder.status.value.summary?.syncState)
    }

    @Test
    fun walksWaitForHealthAccessAndAreSavedOnceGranted() = runTest {
        health.authorization = HealthAuthorization.NotDetermined
        health.bodyMassKg = 82.0
        val (session, recorder) = recorder()
        walk(session, 120_000)
        recorder.end()
        // The walk ends once the belt reports stopped.
        advanceTimeBy(3_000)
        assertEquals(SyncState.Pending, recorder.status.value.summary?.syncState)
        assertTrue(health.saved.isEmpty())

        recorder.requestHealthAccess()
        runCurrent()

        assertEquals(SyncState.Saved, recorder.status.value.summary?.syncState)
        assertEquals(HealthPrompt.Accepted, recorder.status.value.health.prompt)
        assertEquals(WeightSource.Health, recorder.status.value.weight.source)
        assertTrue(recorder.status.value.health.showPriorityTip)

        recorder.dismissSummary()
        assertNull(recorder.status.value.summary)
        assertFalse(recorder.status.value.health.showPriorityTip)
    }

    @Test
    fun decliningHealthAccessKeepsWalksLocal() = runTest {
        health.authorization = HealthAuthorization.NotDetermined
        val (session, recorder) = recorder()
        walk(session, 120_000)
        recorder.end()
        // The walk ends once the belt reports stopped.
        advanceTimeBy(3_000)

        recorder.declineHealthAccess()

        assertEquals(SyncState.NotAuthorized, recorder.status.value.summary?.syncState)
        assertTrue(health.saved.isEmpty())
    }

    @Test
    fun aWalkInProgressSurvivesProcessDeath() = runTest {
        val (session, recorder) = recorder()
        walk(session, 125_000)
        assertEquals(WorkoutPhase.Active, recorder.status.value.phase)
        assertTrue(health.saved.isEmpty())

        // A new process finds the checkpoint; the walk was cut off long enough ago to have ended.
        val restartedSession = TreadmillSession(MockTransportAdapter(), MockAssociationAdapter(), backgroundScope, testScheduler.timeSource)
        val restored = WorkoutRecorder(
            restartedSession,
            health, WorkoutStore(storage), backgroundScope,
            { epoch + testScheduler.currentTime + WorkoutRules.DISCONNECT_TIMEOUT_MS }, { "walk-2" },
        )
        restartedSession.start()
        restored.start()
        runCurrent()

        // Recovered up to the last checkpoint, taken when the second one-minute window closed.
        val saved = assertNotNull(health.saved["walk-1"])
        assertEquals(2, saved.samples.size)
        assertTrue(saved.activeSeconds in 115..120, "activeSeconds=${saved.activeSeconds}")
        assertEquals(saved.distanceMeters, saved.samples.sumOf { it.distanceMeters })
        assertEquals(WorkoutPhase.Idle, restored.status.value.phase)
    }

    @Test
    fun deletingTheSummaryRemovesTheWalkFromHealth() = runTest {
        val (session, recorder) = recorder()
        walk(session, 120_000)
        recorder.end()
        // The walk ends once the belt reports stopped.
        advanceTimeBy(3_000)
        assertNotNull(health.saved["walk-1"])

        recorder.deleteSummary()
        runCurrent()

        assertTrue(health.saved.isEmpty())
        assertNull(recorder.status.value.summary)

        // Nor does it come back on the next launch.
        val (_, relaunched) = recorder()
        assertNull(relaunched.status.value.summary)
        assertNull(WorkoutStore(storage).record("walk-1"))
    }

    @Test
    fun aWalkTheStoreCouldNotDeleteIsKeptAndSavedAgain() = runTest {
        val (session, recorder) = recorder()
        walk(session, 120_000)
        recorder.end()
        // The walk ends once the belt reports stopped.
        advanceTimeBy(3_000)
        health.failNextDelete = IllegalStateException("Store unavailable")

        recorder.deleteSummary()
        runCurrent()

        assertNotNull(health.saved["walk-1"])
        assertEquals(SyncState.Failed("Store unavailable"), recorder.status.value.summary?.syncState)
        val (_, relaunched) = recorder()
        assertEquals("walk-1", relaunched.status.value.summary?.id)

        // The retry writes it again in full, in case the store dropped part of it; then it can be deleted.
        relaunched.retryPendingSync()
        runCurrent()
        assertEquals(SyncState.Saved, relaunched.status.value.summary?.syncState)
        relaunched.deleteSummary()
        runCurrent()
        assertTrue(health.saved.isEmpty())
        assertNull(relaunched.status.value.summary)
    }

    @Test
    fun aPhoneWorkoutIsTrackedAndSaved() = runTest {
        val (_, recorder) = recorder()
        recorder.start(WorkoutKind.IndoorWalk)
        runCurrent()
        assertEquals(WorkoutPhase.Active, recorder.status.value.phase)
        assertEquals(WorkoutSource.Phone, recorder.status.value.source)

        for (s in 1..120) {
            advanceTimeBy(1_000)
            health.progress(PadCounters(s, s, s * 2))
            runCurrent()
        }
        assertEquals(120, recorder.status.value.totals.meters)

        recorder.end()
        runCurrent()

        val saved = assertNotNull(health.saved["walk-1"])
        assertEquals(WorkoutSource.Phone, saved.source)
        assertEquals(120, saved.distanceMeters)
        assertEquals(SyncState.Saved, recorder.status.value.summary?.syncState)
    }

    @Test
    fun anOutdoorRunShowsAndSavesItsRoute() = runTest {
        val (_, recorder) = recorder()
        recorder.start(WorkoutKind.OutdoorRun)
        runCurrent()

        val route = mutableListOf<RoutePoint>()
        for (s in 1..120) {
            advanceTimeBy(1_000)
            health.progress(PadCounters(s, s * 3, s * 3))
            val point = RoutePoint(epoch + testScheduler.currentTime, 35.0 + s * 1e-5, 139.0, 40.0, 5.0)
            route += point
            health.route(listOf(point))
            runCurrent()
        }
        assertEquals(route, recorder.status.value.route)

        recorder.end()
        runCurrent()

        val saved = assertNotNull(health.saved["walk-1"])
        assertEquals(WorkoutKind.OutdoorRun, saved.kind)
        assertEquals(route, saved.route)
        assertEquals(route, recorder.status.value.summary?.route)
    }

    /** Reports a position at [lat], [lon] a second after the last, as the phone's GPS would. */
    private fun TestScope.moveTo(lat: Double, lon: Double, seconds: Int) {
        advanceTimeBy(1_000)
        health.progress(PadCounters(seconds, seconds * 3, seconds * 3))
        health.route(listOf(RoutePoint(epoch + testScheduler.currentTime, lat, lon, null, 5.0)))
        runCurrent()
    }

    @Test
    fun aRunAlongACourseIsNavigatedAndStrayingAlertsOncePerExcursion() = runTest {
        val (_, recorder) = recorder()
        recorder.start(WorkoutKind.OutdoorRun, course.id)
        runCurrent()
        assertEquals(course, recorder.status.value.course)
        assertEquals(1_112, recorder.status.value.navigation?.remainingMeters)

        for (s in 1..20) moveTo(0.0, s * 1e-4, s)
        val along = assertNotNull(recorder.status.value.navigation)
        assertTrue(along.onCourse && along.joined)
        assertEquals(1_112 - 222, along.remainingMeters)
        assertEquals(0, recorder.status.value.navigationAlerts)

        // 100 m off to the north for a while, then back.
        for (s in 21..25) moveTo(0.001, s * 1e-4, s)
        assertFalse(recorder.status.value.navigation!!.onCourse)
        assertEquals(1, recorder.status.value.navigationAlerts)
        moveTo(0.0, 26e-4, 26)
        assertTrue(recorder.status.value.navigation!!.onCourse)
        assertEquals(2, recorder.status.value.navigationAlerts)

        recorder.end()
        runCurrent()
        assertNull(recorder.status.value.course)
        assertNull(recorder.status.value.navigation)
    }

    @Test
    fun aRestoredRunFollowsItsCourseFromWhereItWas() = runTest {
        val (_, first) = recorder()
        first.start(WorkoutKind.OutdoorRun, course.id)
        runCurrent()
        for (s in 1..70) moveTo(0.0, s * 1e-4, s)

        // A checkpoint was written when the first minute's window closed.
        val (_, restored) = recorder()
        runCurrent()
        assertEquals(course.id, restored.status.value.course?.id)
        assertTrue(restored.status.value.navigation!!.progressMeters > 600)
    }

    @Test
    fun deniedLocationAccessRejectsAnOutdoorStart() = runTest {
        val (_, recorder) = recorder()
        health.rejectPhoneWith = RejectReason.LocationAccessDenied
        recorder.start(WorkoutKind.OutdoorRun)
        runCurrent()

        assertEquals(WorkoutPhase.Idle, recorder.status.value.phase)
        assertEquals(RejectReason.LocationAccessDenied, recorder.status.value.startRejected)
    }

    @Test
    fun aRejectedStartIsShownUntilDismissed() = runTest {
        val (_, recorder) = recorder()
        health.rejectPhoneWith = RejectReason.MotionAccessDenied
        recorder.start(WorkoutKind.IndoorWalk)
        runCurrent()

        assertEquals(WorkoutPhase.Idle, recorder.status.value.phase)
        assertFalse(recorder.status.value.starting)
        assertEquals(RejectReason.MotionAccessDenied, recorder.status.value.startRejected)

        recorder.dismissStartRejected()
        assertNull(recorder.status.value.startRejected)
    }

    @Test
    fun aDeferredSaveIsSentAgainOnForeground() = runTest {
        val (session, recorder) = recorder()
        health.failNextSave = HealthStoreBusyException("Protected data unavailable")
        walk(session, 150_000)
        recorder.end()
        // The walk ends once the belt reports stopped.
        advanceTimeBy(3_000)
        assertEquals(SyncState.Busy, recorder.status.value.summary?.syncState)

        recorder.retryPendingSync()
        runCurrent()

        assertEquals(SyncState.Saved, recorder.status.value.summary?.syncState)
        assertEquals(2, health.received.count { it is WorkoutEvent.Finished })
    }
}
