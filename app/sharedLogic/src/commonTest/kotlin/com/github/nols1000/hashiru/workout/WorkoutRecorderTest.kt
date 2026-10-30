package com.github.nols1000.hashiru.workout

import com.github.nols1000.hashiru.treadmill.adapter.MockAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.MockTransportAdapter
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.workout.adapter.InMemoryKeyValueStore
import com.github.nols1000.hashiru.workout.adapter.MockHealthSyncAdapter
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.workout.domain.RejectReason
import com.github.nols1000.hashiru.workout.domain.SyncState
import com.github.nols1000.hashiru.workout.domain.WeightSource
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

    private fun TestScope.recorder(): Pair<TreadmillSession, WorkoutRecorder> {
        // The pad accumulates distance on the test clock, so virtual time walks it.
        transport = MockTransportAdapter(targetSpeedTenths = 30, rampStepTenths = 10, timeSource = testScheduler.timeSource)
        val session = TreadmillSession(transport, MockAssociationAdapter(), backgroundScope, testScheduler.timeSource)
        val recorder = WorkoutRecorder(
            session, health, WorkoutStore(storage), backgroundScope, { epoch + testScheduler.currentTime }, { "walk-1" },
        )
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

        advanceTimeBy(WorkoutRules.PAUSE_TIMEOUT_MS)

        assertNotNull(health.saved["walk-1"])
        assertEquals(WorkoutPhase.Idle, recorder.status.value.phase)
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
        val restored = WorkoutRecorder(
            TreadmillSession(MockTransportAdapter(), MockAssociationAdapter(), backgroundScope, testScheduler.timeSource),
            health, WorkoutStore(storage), backgroundScope,
            { epoch + testScheduler.currentTime + WorkoutRules.DISCONNECT_TIMEOUT_MS }, { "walk-2" },
        )
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
