package com.github.nols1000.hashiru.workout

import androidx.lifecycle.ViewModelStore
import com.github.nols1000.hashiru.companion.adapter.MockWatchCompanionAdapter
import com.github.nols1000.hashiru.companion.engine.WatchRelay
import com.github.nols1000.hashiru.treadmill.adapter.MockAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.MockTransportAdapter
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.treadmill.presentation.TreadmillViewModel
import com.github.nols1000.hashiru.workout.adapter.InMemoryKeyValueStore
import com.github.nols1000.hashiru.workout.adapter.MockHealthSyncAdapter
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
import com.github.nols1000.hashiru.workout.presentation.SyncUi
import com.github.nols1000.hashiru.workout.presentation.WorkoutViewModel
import com.github.nols1000.hashiru.workout.store.WorkoutStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    /** The workout screens' two presenters; the treadmill's drives the pad the walk is recorded from. */
    private class Screens(val treadmill: TreadmillViewModel, val workout: WorkoutViewModel)

    private fun TestScope.screensFor(transport: MockTransportAdapter): Screens {
        // Like the app's session scope, a supervisor, so a failed connection attempt fails only the caller.
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val session = TreadmillSession(transport, MockAssociationAdapter(), scope, testScheduler.timeSource)
        val workouts = WorkoutRecorder(
            session, MockHealthSyncAdapter(), WorkoutStore(InMemoryKeyValueStore()), backgroundScope, { testScheduler.currentTime }, { "walk" },
        )
        val relay = WatchRelay(session, workouts, MockWatchCompanionAdapter(simulateHeartRate = false), backgroundScope)
        session.start()
        workouts.start()
        relay.start()
        return Screens(
            TreadmillViewModel(session).also { store.put("treadmill", it) },
            WorkoutViewModel(session.connectionState, workouts, relay).also { store.put("workout", it) },
        )
    }

    @Test
    fun aLostConnectionEscalatesAfterThirtySeconds() = runTest(dispatcher) {
        val transport = MockTransportAdapter(targetSpeedTenths = 30, rampStepTenths = 10)
        val screens = screensFor(transport)
        val viewModel = screens.workout
        runCurrent()
        screens.treadmill.connect()
        advanceTimeBy(600)
        screens.treadmill.toggleBelt()
        advanceTimeBy(5_000)
        assertEquals(WorkoutPhase.Active, viewModel.uiState.value.phase)

        transport.simulateConnectionLoss()
        advanceTimeBy(1_000)
        with(viewModel.uiState.value) {
            assertEquals(PauseReason.Disconnected, pauseReason)
            assertFalse(connectionLost)
        }

        advanceTimeBy(WorkoutViewModel.CONNECTION_LOST_SECONDS * 1_000L)
        assertTrue(viewModel.uiState.value.connectionLost)
    }

    @Test
    fun aWalkIsTrackedEndedAndSummarised() = runTest(dispatcher) {
        val transport = MockTransportAdapter(targetSpeedTenths = 30, rampStepTenths = 10, timeSource = testScheduler.timeSource)
        val screens = screensFor(transport)
        val viewModel = screens.workout
        screens.treadmill.connect()
        advanceTimeBy(600)
        assertTrue(viewModel.uiState.value.health.showConnectCard)

        screens.treadmill.toggleBelt()
        advanceTimeBy(120_000)
        with(viewModel.uiState.value) {
            assertEquals(WorkoutPhase.Active, phase)
            assertTrue(distanceMeters > 0)
            assertTrue(activeKcal > 0)
            assertTrue(weightEstimated)
        }

        viewModel.pauseWorkout()
        advanceTimeBy(2_000)
        assertEquals(WorkoutPhase.Paused, viewModel.uiState.value.phase)
        assertFalse(screens.treadmill.uiState.value.isRunning)

        viewModel.endWorkout()
        advanceTimeBy(1_000)
        val summary = viewModel.uiState.value.summary
        assertEquals(WorkoutPhase.Idle, viewModel.uiState.value.phase)
        assertEquals(SyncUi.WaitingForAccess, summary?.sync)

        viewModel.connectHealth()
        advanceTimeBy(1_000)
        assertEquals(SyncUi.Saved, viewModel.uiState.value.summary?.sync)
        assertFalse(viewModel.uiState.value.health.showConnectCard)

        viewModel.dismissSummary()
        runCurrent()
        assertNull(viewModel.uiState.value.summary)
    }

    @Test
    fun thePaceHoldsBetweenTheDistanceSteps() = runTest(dispatcher) {
        val transport = MockTransportAdapter(targetSpeedTenths = 30, rampStepTenths = 30, timeSource = testScheduler.timeSource)
        val screens = screensFor(transport)
        val viewModel = screens.workout
        screens.treadmill.connect()
        advanceTimeBy(600)
        screens.treadmill.toggleBelt()
        advanceTimeBy(120_000)

        // At 3 km/h the pad reports 10 m every 12 s; the pace changes only with them and stays near 20:00/km.
        var last = viewModel.uiState.value
        repeat(60) {
            advanceTimeBy(1_000)
            val now = viewModel.uiState.value
            if (now.distanceMeters == last.distanceMeters) assertEquals(last.paceSecondsPerKm, now.paceSecondsPerKm)
            assertTrue(now.paceSecondsPerKm!! in 1180..1260, "pace ${now.paceSecondsPerKm}")
            last = now
        }
    }
}
