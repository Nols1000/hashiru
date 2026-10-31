package com.github.nols1000.hashiru.workout

import androidx.lifecycle.ViewModelStore
import com.github.nols1000.hashiru.treadmill.adapter.MockAssociationAdapter
import com.github.nols1000.hashiru.treadmill.adapter.MockTransportAdapter
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.workout.adapter.InMemoryKeyValueStore
import com.github.nols1000.hashiru.workout.adapter.MockHealthSyncAdapter
import com.github.nols1000.hashiru.workout.adapter.MockWorkoutHistoryAdapter
import com.github.nols1000.hashiru.workout.domain.PastWalk
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.WalkTrends.DAY_MS
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
import com.github.nols1000.hashiru.workout.port.HealthAuthorization
import com.github.nols1000.hashiru.workout.presentation.HistoryViewModel
import com.github.nols1000.hashiru.workout.store.WorkoutStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    private val now = 1_700_000_000_000L
    private val storage = InMemoryKeyValueStore()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun walk(daysAgo: Int, recordId: String? = null) = PastWalk(
        id = "w$daysAgo", startMs = now - daysAgo * DAY_MS, endMs = now - daysAgo * DAY_MS + 1_800_000, durationSeconds = 1_800,
        distanceMeters = 2_000, steps = 2_500, activeKcal = 70, indoor = true, sourceName = "hashiru", recordId = recordId,
    )

    private fun TestScope.viewModel(
        history: MockWorkoutHistoryAdapter,
        authorization: HealthAuthorization = HealthAuthorization.Granted,
    ): HistoryViewModel {
        val session = TreadmillSession(MockTransportAdapter(), MockAssociationAdapter(), backgroundScope, testScheduler.timeSource)
        val recorder = WorkoutRecorder(
            session, MockHealthSyncAdapter(authorization = authorization), WorkoutStore(storage), backgroundScope, { now }, { "walk" },
        )
        session.start()
        recorder.start()
        val weekDays = { _: Long -> (0..7).map { now - 3 * DAY_MS + it * DAY_MS } }
        return HistoryViewModel(history, recorder, WorkoutStore(storage), weekDays, { now }).also { viewModels.put("history", it) }
    }

    @Test
    fun anOutdoorWalksRouteIsLoaded() = runTest(dispatcher) {
        val outdoor = walk(1).copy(indoor = false)
        val route = listOf(RoutePoint(outdoor.startMs, 35.0, 139.0), RoutePoint(outdoor.startMs + 1_000, 35.00001, 139.0))
        val viewModel = viewModel(MockWorkoutHistoryAdapter(listOf(outdoor), routes = mapOf(outdoor.id to route, "w2" to route)))
        var loaded: List<RoutePoint>? = null
        viewModel.route(outdoor) { loaded = it }
        runCurrent()
        assertEquals(route, loaded)

        // Indoors there is nothing to read.
        viewModel.route(walk(2)) { loaded = it }
        assertEquals(emptyList(), loaded)
    }

    @Test
    fun nothingIsReadBeforeHealthIsConnected() = runTest(dispatcher) {
        val history = MockWorkoutHistoryAdapter(listOf(walk(1)))
        val viewModel = viewModel(history, HealthAuthorization.NotDetermined)
        runCurrent()

        assertFalse(viewModel.uiState.value.healthConnected)
        assertTrue(viewModel.uiState.value.walks.isEmpty())
        assertEquals(0, history.readAccessRequests)
    }

    @Test
    fun loadsTheLastNinetyDaysAndAsksForReadAccessOnce() = runTest(dispatcher) {
        val history = MockWorkoutHistoryAdapter(listOf(walk(0), walk(1), walk(20), walk(120)))
        val viewModel = viewModel(history)
        runCurrent()

        with(viewModel.uiState.value) {
            assertTrue(healthConnected)
            assertEquals(listOf("w0", "w1", "w20"), walks.map { it.id })
            assertEquals(2, week.walks)
            assertEquals(4_000, week.distanceMeters)
            assertTrue(trends.isNotEmpty())
        }

        viewModel.refresh()
        runCurrent()
        assertEquals(1, history.readAccessRequests)
    }

    @Test
    fun aFailedReadKeepsTheWalksAndSaysSo() = runTest(dispatcher) {
        val history = MockWorkoutHistoryAdapter(listOf(walk(1)))
        val viewModel = viewModel(history)
        runCurrent()
        assertFalse(viewModel.uiState.value.loadFailed)

        history.failReads = true
        viewModel.refresh()
        runCurrent()
        with(viewModel.uiState.value) {
            assertTrue(loadFailed)
            assertEquals(listOf("w1"), walks.map { it.id })
        }

        history.failReads = false
        viewModel.refresh()
        runCurrent()
        assertFalse(viewModel.uiState.value.loadFailed)
    }

    @Test
    fun loadMorePagesIntoOlderWalks() = runTest(dispatcher) {
        val history = MockWorkoutHistoryAdapter(listOf(walk(1), walk(100), walk(200)))
        val viewModel = viewModel(history)
        runCurrent()
        assertEquals(listOf("w1"), viewModel.uiState.value.walks.map { it.id })

        viewModel.loadMore()
        runCurrent()

        with(viewModel.uiState.value) {
            assertEquals(listOf("w1", "w100", "w200"), walks.map { it.id })
            assertFalse(hasMore)
        }
    }

    @Test
    fun onlyTheAppsOwnWalksAreDeleted() = runTest(dispatcher) {
        val own = walk(1, recordId = "walk-1")
        val other = walk(2)
        val history = MockWorkoutHistoryAdapter(listOf(own, other))
        val viewModel = viewModel(history)
        runCurrent()

        viewModel.delete(other)
        viewModel.delete(own)
        runCurrent()

        assertEquals(listOf("w2"), history.walks.map { it.id })
        assertEquals(listOf("w2"), viewModel.uiState.value.walks.map { it.id })
    }

    @Test
    fun theWeeklyGoalIsKept() = runTest(dispatcher) {
        val viewModel = viewModel(MockWorkoutHistoryAdapter())
        viewModel.setWeeklyGoal(15_000)

        assertEquals(15_000, viewModel.uiState.value.weeklyGoalMeters)
        assertEquals(15_000, WorkoutStore(storage).weeklyGoalMeters)
    }
}
