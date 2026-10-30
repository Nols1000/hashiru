package com.github.nols1000.hashiru.workout.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.nols1000.hashiru.workout.domain.HeartRateZone
import com.github.nols1000.hashiru.workout.domain.PastWalk
import com.github.nols1000.hashiru.workout.domain.SyncState
import com.github.nols1000.hashiru.workout.domain.WalkTrend
import com.github.nols1000.hashiru.workout.domain.WalkTrends
import com.github.nols1000.hashiru.workout.domain.WeekSummary
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
import com.github.nols1000.hashiru.workout.port.HealthAuthorization
import com.github.nols1000.hashiru.workout.port.HealthAvailability
import com.github.nols1000.hashiru.workout.port.KeyValueStorePort
import com.github.nols1000.hashiru.workout.port.WorkoutHistoryPort
import com.github.nols1000.hashiru.workout.store.WorkoutStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class HistoryUiState(
    /** False until the user has answered the health store's permission sheet; there is nothing to read before. */
    val healthConnected: Boolean = false,
    val isLoading: Boolean = false,
    /** True when the last read from the health store failed; [walks] then still holds what was loaded before. */
    val loadFailed: Boolean = false,
    /** Newest first: the last [WalkTrends.BASELINE_DAYS] days, plus older pages once loaded. */
    val walks: List<PastWalk> = emptyList(),
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val week: WeekSummary = WeekSummary(),
    val weeklyGoalMeters: Int = WorkoutStore.DEFAULT_WEEKLY_GOAL_METERS,
    val trends: List<WalkTrend> = emptyList(),
)

/** The state before anything is known; for Swift, which cannot use the constructor's default arguments. */
fun initialHistoryUiState(): HistoryUiState = HistoryUiState()

data class HeartRatePointUi(val epochMs: Long, val bpm: Int, val heartRateZone: HeartRateZone?)

/** Heart rate during one past walk; empty without a watch or without read access. */
data class HeartRateUi(val points: List<HeartRatePointUi>, val avgBpm: Int?, val maxBpm: Int?)

/**
 * Presents the walks in the health store: the summary page's week, trends and recent sessions, and the full list.
 * It reloads whenever the recorder saves or deletes a walk, or health access changes.
 *
 * [weekDayStarts] gives the start of each day of the week containing a time, then the start of the next week,
 * in the local calendar.
 */
class HistoryViewModel internal constructor(
    private val history: WorkoutHistoryPort,
    private val workouts: WorkoutRecorder,
    private val store: WorkoutStore,
    private val weekDayStarts: (nowMs: Long) -> List<Long>,
    private val nowMs: () -> Long,
) : ViewModel() {
    @OptIn(ExperimentalTime::class)
    constructor(
        history: WorkoutHistoryPort,
        workouts: WorkoutRecorder,
        storage: KeyValueStorePort,
        weekDayStarts: (nowMs: Long) -> List<Long>,
    ) : this(history, workouts, WorkoutStore(storage), weekDayStarts, { Clock.System.now().toEpochMilliseconds() })

    private data class Reload(val connected: Boolean, val summaryId: String?, val summarySync: SyncState?)

    private val _uiState = MutableStateFlow(HistoryUiState(weeklyGoalMeters = store.weeklyGoalMeters))
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var readAccessRequested = false

    init {
        viewModelScope.launch {
            workouts.status
                .map {
                    val connected = it.health.availability == HealthAvailability.Available &&
                        it.health.authorization != HealthAuthorization.NotDetermined
                    Reload(connected, it.summary?.id, it.summary?.syncState)
                }
                .distinctUntilChanged()
                .collect {
                    _uiState.update { state -> state.copy(healthConnected = it.connected) }
                    refresh()
                }
        }
    }

    /** Reloads the last [WalkTrends.BASELINE_DAYS] days; older pages loaded so far are dropped. */
    fun refresh() {
        if (!_uiState.value.healthConnected) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            if (!readAccessRequested) {
                // Walks saved before reading was offered need the sheet once more, for the read types alone.
                readAccessRequested = true
                report { history.requestReadAccess() }
            }
            val walks = report { history.walks(sinceMs = nowMs() - WalkTrends.BASELINE_DAYS * WalkTrends.DAY_MS, beforeMs = null, limit = RECENT_LIMIT) }
            _uiState.update {
                publish(it.copy(isLoading = false, isLoadingMore = false, loadFailed = walks == null, walks = walks ?: it.walks, hasMore = walks != null))
            }
        }
    }

    /** Loads the next page of older walks for the session list. */
    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoadingMore || state.isLoading) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val before = state.walks.lastOrNull()?.endMs ?: nowMs()
            val page = report { history.walks(sinceMs = null, beforeMs = before, limit = PAGE_SIZE) }
            _uiState.update { current ->
                val known = current.walks.mapTo(HashSet()) { it.id }
                current.copy(
                    isLoadingMore = false,
                    loadFailed = page == null,
                    walks = current.walks + page.orEmpty().filter { it.id !in known },
                    hasMore = page != null && page.size == PAGE_SIZE,
                )
            }
        }
    }

    /** Removes a walk this app recorded from the health store. */
    fun delete(walk: PastWalk) {
        if (!walk.recordedByApp) return
        viewModelScope.launch {
            report { history.delete(walk) } ?: return@launch
            _uiState.update { publish(it.copy(walks = it.walks.filter { w -> w.id != walk.id })) }
        }
    }

    /** Loads the heart rate during [walk] and hands it to [onResult] on the main thread. */
    fun heartRate(walk: PastWalk, onResult: (HeartRateUi) -> Unit) {
        viewModelScope.launch {
            val age = workouts.status.value.profile.ageYears
            val samples = report { history.heartRate(walk) }.orEmpty()
            onResult(
                HeartRateUi(
                    points = samples.map { HeartRatePointUi(it.epochMs, it.bpm, HeartRateZone.of(it.bpm, age)) },
                    avgBpm = samples.takeIf { it.isNotEmpty() }?.let { s -> s.sumOf { it.bpm } / s.size },
                    maxBpm = samples.maxOfOrNull { it.bpm },
                ),
            )
        }
    }

    fun setWeeklyGoal(meters: Int) {
        store.weeklyGoalMeters = meters.coerceIn(MIN_GOAL_METERS, MAX_GOAL_METERS)
        _uiState.update { it.copy(weeklyGoalMeters = store.weeklyGoalMeters) }
    }

    /** Call when the app comes to the foreground: other apps may have added walks, and the week may have turned. */
    fun onForeground() = refresh()

    /** Swift-friendly observation of [uiState]; call `close()` on the result to stop. */
    fun watch(onChange: (HistoryUiState) -> Unit): AutoCloseable {
        val job = viewModelScope.launch { uiState.collect { onChange(it) } }
        return AutoCloseable { job.cancel() }
    }

    private fun publish(state: HistoryUiState): HistoryUiState {
        val now = nowMs()
        return state.copy(
            week = WalkTrends.week(state.walks, weekDayStarts(now)),
            trends = WalkTrends.trends(state.walks, now),
        )
    }

    /** Runs [block], logging instead of throwing; a failed read leaves what is already shown. */
    private suspend fun <T> report(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("HistoryViewModel: ${e.message}")
        null
    }

    companion object {
        /** Enough for the summary's 90 days of almost anyone; more than that only thins the trends. */
        private const val RECENT_LIMIT = 500
        private const val PAGE_SIZE = 50
        const val MIN_GOAL_METERS = 1_000
        const val MAX_GOAL_METERS = 200_000
    }
}
