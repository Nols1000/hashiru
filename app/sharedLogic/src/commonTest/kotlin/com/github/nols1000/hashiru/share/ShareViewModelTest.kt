package com.github.nols1000.hashiru.share

import androidx.lifecycle.ViewModelStore
import com.github.nols1000.hashiru.share.adapter.MockShareRelayAdapter
import com.github.nols1000.hashiru.share.engine.LiveShareEngine
import com.github.nols1000.hashiru.share.presentation.ShareUiPhase
import com.github.nols1000.hashiru.share.presentation.ShareViewModel
import com.github.nols1000.hashiru.treadmill.adapter.MockAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.MockTransportAdapter
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.workout.adapter.InMemoryKeyValueStore
import com.github.nols1000.hashiru.workout.adapter.MockHealthSyncAdapter
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
import com.github.nols1000.hashiru.workout.store.WorkoutStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun shareStatusReachesTheUiState() = runTest(dispatcher) {
        val relay = MockShareRelayAdapter()
        val session = TreadmillSession(MockTransportAdapter(), MockAssociationAdapter(), backgroundScope, testScheduler.timeSource)
        val workouts = WorkoutRecorder(
            session, MockHealthSyncAdapter(), WorkoutStore(InMemoryKeyValueStore()), backgroundScope, { testScheduler.currentTime }, { "walk" },
        )
        val share = LiveShareEngine(
            workouts.status,
            relay,
            FakeShareCipher(),
            { 80 },
            InMemoryKeyValueStore(),
            "https://share.test",
            backgroundScope,
            { testScheduler.currentTime },
        )
        session.start()
        workouts.start()
        share.start()
        val viewModel = ShareViewModel(share).also { store.put("share", it) }
        workouts.start(WorkoutKind.IndoorWalk, null)
        runCurrent()
        assertEquals(WorkoutPhase.Active, workouts.status.value.phase)

        viewModel.startShare(true)
        runCurrent()
        with(viewModel.uiState.value) {
            assertEquals(ShareUiPhase.Live, phase)
            assertTrue(link?.startsWith("https://share.test/#v1.") == true)
            assertTrue(includeHeartRate)
            assertFalse(waitingForRoom)
        }
        viewModel.stopShare()
        runCurrent()

        // A full relay keeps the share waiting for room, which the UI tells of until it opens.
        relay.full = true
        viewModel.startShare(false)
        runCurrent()
        with(viewModel.uiState.value) {
            assertEquals(ShareUiPhase.Live, phase)
            assertTrue(waitingForRoom)
            assertFalse(offline)
        }
        relay.full = false
        runCurrent()
        assertFalse(viewModel.uiState.value.waitingForRoom)

        viewModel.stopShare()
        runCurrent()
        assertEquals(ShareUiPhase.Off, viewModel.uiState.value.phase)
    }
}
