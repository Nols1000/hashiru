package com.github.nols1000.hashiru.treadmill

import androidx.lifecycle.ViewModelStore
import com.github.nols1000.hashiru.companion.adapter.MockWatchCompanionAdapter
import com.github.nols1000.hashiru.companion.engine.WatchRelay
import com.github.nols1000.hashiru.treadmill.adapter.MockAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.MockTransportAdapter
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.treadmill.domain.TreadmillError
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.treadmill.presentation.TreadmillViewModel
import com.github.nols1000.hashiru.workout.adapter.InMemoryKeyValueStore
import com.github.nols1000.hashiru.workout.adapter.MockHealthSyncAdapter
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
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
class TreadmillViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    /** Starts the session with the recorder and relay beside it, in the app's order, as they act on the belt too. */
    private fun TestScope.start(session: TreadmillSession) {
        val workouts = WorkoutRecorder(
            session, MockHealthSyncAdapter(), WorkoutStore(InMemoryKeyValueStore()), backgroundScope, { testScheduler.currentTime }, { "walk" },
        )
        val relay = WatchRelay(session, workouts, MockWatchCompanionAdapter(simulateHeartRate = false), backgroundScope)
        session.start()
        workouts.start()
        relay.start()
    }

    private fun TestScope.viewModelFor(
        transport: MockTransportAdapter,
        associations: MockAssociationAdapter = MockAssociationAdapter(),
    ): TreadmillViewModel {
        // Like the app's session scope, a supervisor, so a failed connection attempt fails only the caller.
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val session = TreadmillSession(transport, associations, scope, testScheduler.timeSource)
        start(session)
        return TreadmillViewModel(session).also { store.put("treadmill", it) }
    }

    @Test
    fun connectStartAndStopAgainstMock() = runTest(dispatcher) {
        val transport = MockTransportAdapter(targetSpeedTenths = 30, rampStepTenths = 10)
        val viewModel = viewModelFor(transport)
        runCurrent()
        assertFalse(viewModel.uiState.value.isConnected)

        viewModel.connect()
        advanceTimeBy(600)
        assertTrue(viewModel.uiState.value.isConnected)

        viewModel.toggleBelt()
        advanceTimeBy(3_000)
        with(viewModel.uiState.value) {
            assertTrue(isRunning)
            assertEquals(3.0, speedKmh)
        }

        viewModel.toggleBelt()
        advanceTimeBy(1_000)
        with(viewModel.uiState.value) {
            assertFalse(isRunning)
            assertEquals(0.0, speedKmh)
        }

        viewModel.disconnect()
        runCurrent()
        assertFalse(viewModel.uiState.value.isConnected)
    }

    @Test
    fun speedStepperSnapsToHalfKmhWithinBounds() = runTest(dispatcher) {
        val transport = MockTransportAdapter(targetSpeedTenths = 30, rampStepTenths = 10)
        val viewModel = viewModelFor(transport)
        viewModel.connect()
        advanceTimeBy(600)

        assertFalse(viewModel.uiState.value.canIncreaseSpeed)
        viewModel.increaseSpeed() // ignored while idle

        viewModel.toggleBelt()
        advanceTimeBy(3_000)
        assertEquals(3.0, viewModel.uiState.value.speedKmh)

        // Rapid taps build on the pending target rather than the stale reported speed.
        viewModel.increaseSpeed()
        viewModel.increaseSpeed()
        advanceTimeBy(3_000)
        assertEquals(4.0, viewModel.uiState.value.speedKmh)

        repeat(10) { viewModel.increaseSpeed() }
        advanceTimeBy(5_000)
        with(viewModel.uiState.value) {
            assertEquals(6.0, speedKmh)
            assertFalse(canIncreaseSpeed)
            assertTrue(canDecreaseSpeed)
        }

        repeat(20) { viewModel.decreaseSpeed() }
        advanceTimeBy(8_000)
        with(viewModel.uiState.value) {
            assertEquals(0.5, speedKmh)
            assertTrue(canIncreaseSpeed)
            assertFalse(canDecreaseSpeed)
        }
    }

    @Test
    fun dismissErrorClearsFailure() = runTest(dispatcher) {
        val viewModel = viewModelFor(MockTransportAdapter())
        runCurrent()

        viewModel.toggleBelt() // mock throws "Not connected"
        runCurrent()
        assertEquals(TreadmillError.CommandFailed(TreadmillCommand.StartBelt), viewModel.uiState.value.error)

        viewModel.dismissError()
        runCurrent()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun aFailedConnectionShowsItsTypedError() = runTest(dispatcher) {
        val transport = MockTransportAdapter().apply { failNextConnect = TreadmillError.BluetoothOff }
        val viewModel = viewModelFor(transport)
        runCurrent()

        viewModel.connect()
        advanceTimeBy(600)

        with(viewModel.uiState.value) {
            assertEquals(TreadmillError.BluetoothOff, error)
            assertFalse(isConnected)
            assertFalse(isConnecting)
        }
    }

    @Test
    fun connectingAgainClearsThePreviousError() = runTest(dispatcher) {
        val transport = MockTransportAdapter().apply { failNextConnect = TreadmillError.PadNotFound(2) }
        val viewModel = viewModelFor(transport)
        runCurrent()
        viewModel.connect()
        advanceTimeBy(600)
        assertEquals(TreadmillError.PadNotFound(2), viewModel.uiState.value.error)

        viewModel.connect()
        runCurrent()
        assertNull(viewModel.uiState.value.error)
        advanceTimeBy(600)
        assertTrue(viewModel.uiState.value.isConnected)
    }

    @Test
    fun cancellingThePickerIsNotAnError() = runTest(dispatcher) {
        val associations = MockAssociationAdapter().apply { cancelNextPick = true }
        val viewModel = viewModelFor(MockTransportAdapter(), associations)
        runCurrent()

        viewModel.pair()
        advanceTimeBy(600)

        with(viewModel.uiState.value) {
            assertNull(error)
            assertFalse(isPaired)
            assertFalse(isConnected)
        }
    }

    @Test
    fun newViewModelShowsTheRunningSessionImmediately() = runTest(dispatcher) {
        val transport = MockTransportAdapter(targetSpeedTenths = 30, rampStepTenths = 10)
        val session = TreadmillSession(transport, MockAssociationAdapter(), backgroundScope, testScheduler.timeSource)
        start(session)
        val first = TreadmillViewModel(session).also { store.put("first", it) }
        first.connect()
        advanceTimeBy(600)
        first.toggleBelt()
        advanceTimeBy(3_000)

        // The screen goes away; the session keeps the connection and the belt running.
        store.clear()
        advanceTimeBy(1_000)
        assertEquals(ConnectionState.Connected, transport.connectionState.value)

        val second = TreadmillViewModel(session).also { store.put("second", it) }
        runCurrent()
        with(second.uiState.value) {
            assertTrue(isConnected)
            assertTrue(isRunning)
            assertEquals(3.0, speedKmh)
        }
    }
}
