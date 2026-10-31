package com.github.nols1000.hashiru.workout.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.nols1000.hashiru.companion.domain.CompanionState
import com.github.nols1000.hashiru.companion.engine.WatchRelay
import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.domain.NavigationStatus
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.workout.domain.HeartRateZone
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.workout.domain.RejectReason
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.Sex
import com.github.nols1000.hashiru.workout.domain.SyncState
import com.github.nols1000.hashiru.workout.domain.WeightSource
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.domain.WorkoutRecord
import com.github.nols1000.hashiru.workout.domain.WorkoutSource
import com.github.nols1000.hashiru.workout.engine.WorkoutPhase
import com.github.nols1000.hashiru.workout.engine.WorkoutRecorder
import com.github.nols1000.hashiru.workout.engine.WorkoutRules
import com.github.nols1000.hashiru.workout.engine.WorkoutStatus
import com.github.nols1000.hashiru.workout.port.HealthAuthorization
import com.github.nols1000.hashiru.workout.port.HealthAvailability
import com.github.nols1000.hashiru.workout.store.HealthPrompt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The workout in progress, across pauses, plus the last finished workout and the state of health sync. */
data class WorkoutUiState(
    val phase: WorkoutPhase = WorkoutPhase.Idle,
    val kind: WorkoutKind = WorkoutKind.IndoorWalk,
    /** True when the WalkingPad records the workout; false when the phone does. */
    val usesPad: Boolean = true,
    /** A phone workout was asked for and has not started yet. */
    val starting: Boolean = false,
    /** Why the last workout asked for did not start, until dismissed. */
    val startRejected: RejectReason? = null,
    val pauseReason: PauseReason? = null,
    val pausedSeconds: Int = 0,
    val durationSeconds: Int = 0,
    val distanceMeters: Int = 0,
    val steps: Int = 0,
    val activeKcal: Int = 0,
    /** Null until the walk is long enough for a meaningful pace. */
    val paceSecondsPerKm: Int? = null,
    /** True when calories use the default weight because none is known. */
    val weightEstimated: Boolean = true,
    val manualWeightKg: Double? = null,
    /** The weight from the health store, which calories use over [manualWeightKg]; null when it has none. */
    val healthWeightKg: Double? = null,
    /** The latest reading from the watch; null without a watch or once readings stopped. */
    val heartRateBpm: Int? = null,
    /** Null without a heart rate or without a known age. */
    val heartRateZone: HeartRateZone? = null,
    /** Where the outdoor workout in progress went so far, for the live map. */
    val route: List<RoutePoint> = emptyList(),
    /** The course the workout follows, and where it stands on it; null without one. */
    val course: Course? = null,
    val navigation: NavigationStatus? = null,
    /** Goes up each time the workout strays from its course or comes back; alert when it changes. */
    val navigationAlerts: Int = 0,
    val companion: CompanionState = CompanionState.NotPaired,
    /** Offer the watch app once, while the phone has a watch without it. */
    val showWatchTip: Boolean = false,
    val manualBirthYear: Int? = null,
    val manualSex: Sex? = null,
    /** True when the health store provides the age, so the app does not ask for a birth year. */
    val ageFromHealth: Boolean = false,
    val sexFromHealth: Boolean = false,
    val summary: WorkoutSummaryUi? = null,
    /** The last walk was under [minSeconds] or [minMeters] and was not saved; shown until dismissed. */
    val tooShort: Boolean = false,
    val health: HealthUi = HealthUi(),
) {
    val minSeconds: Int get() = WorkoutRules.MIN_ACTIVE_SECONDS
    val minMeters: Int get() = WorkoutRules.MIN_DISTANCE_METERS

    val isInProgress: Boolean get() = phase != WorkoutPhase.Idle

    /** Paused by a lost connection for long enough that the pad is probably off or out of range. */
    val connectionLost: Boolean
        get() = usesPad && pauseReason == PauseReason.Disconnected && pausedSeconds >= WorkoutViewModel.CONNECTION_LOST_SECONDS
}

/** The state before anything is known; for Swift, which cannot use the constructor's default arguments. */
fun initialWorkoutUiState(): WorkoutUiState = WorkoutUiState()

/**
 * [Busy]: the health store cannot be written right now (phone locked); the walk is saved on the next foreground.
 * [DeleteFailed]: the store could not delete the walk; it is kept, and a retry writes it again so it can be deleted.
 */
enum class SyncUi { Saving, WaitingForAccess, Busy, Saved, Failed, DeleteFailed, NotSaved, Unavailable }

data class WorkoutSummaryUi(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val durationSeconds: Int,
    val distanceMeters: Int,
    val steps: Int,
    val activeKcal: Int,
    val totalKcal: Int,
    val paceSecondsPerKm: Int?,
    val weightEstimated: Boolean,
    val sync: SyncUi,
    val syncMessage: String?,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    /** True when calories came from heart rate for at least part of the walk. */
    val energyFromHeartRate: Boolean = false,
    val kind: WorkoutKind = WorkoutKind.IndoorWalk,
    /** Where an outdoor workout went; empty indoors. */
    val route: List<RoutePoint> = emptyList(),
)

data class HealthUi(
    val available: Boolean = false,
    /** Offer to save walks to the health store; shown once connected, until the user answers. */
    val showConnectCard: Boolean = false,
    val accessDenied: Boolean = false,
    val showPriorityTip: Boolean = false,
    /** Offer to install the health store where the platform has one that is missing; shown until the user answers. */
    val showInstallCard: Boolean = false,
)

/**
 * Presents the [WorkoutRecorder]: the workout in progress, the summary of the last one, health sync, the profile
 * calories are counted with, and the watch beside it. The cards it offers wait for the treadmill's [connection].
 * The recorder and the relay outlive this ViewModel; they are started by whoever built them.
 */
class WorkoutViewModel(
    private val connection: StateFlow<ConnectionState>,
    private val workouts: WorkoutRecorder,
    private val relay: WatchRelay,
) : ViewModel() {
    /**
     * A status with the walk's time and distance when the distance last changed. The pad counts distance in 10 m
     * steps, so only then do the two agree; a pace from them does not drift between steps.
     */
    private class Paced(val status: WorkoutStatus, val paceAt: PadCounters)

    private val paced = workouts.status
        .scan<WorkoutStatus, Paced?>(null) { last, status ->
            val at = last?.paceAt ?: PadCounters.Zero
            val totals = status.totals
            Paced(status, if (totals.meters != at.meters || totals.seconds < at.seconds) totals else at)
        }
        .filterNotNull()

    val uiState: StateFlow<WorkoutUiState> =
        combine(connection, paced, relay.heartRate, relay.companion) { connection, paced, heart, companion ->
            val connected = connection == ConnectionState.Connected
            val status = paced.status
            // The health store's reading where it measures heart rate, else the watch's own message.
            val bpm = status.heartRateBpm ?: heart?.bpm
            status.toUi(connected, paced.paceAt).copy(
                heartRateBpm = bpm,
                heartRateZone = bpm?.let { HeartRateZone.of(it, status.profile.ageYears) },
                companion = companion,
                showWatchTip = connected && companion == CompanionState.AppNotInstalled && !status.watchTipDismissed,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, WorkoutUiState())

    /** Starts a phone workout of [kind], following the course [courseId] where there is one; with the pad, starting the belt starts the walk instead. */
    fun startWorkout(kind: WorkoutKind, courseId: String?) = workouts.start(kind, courseId)
    fun dismissStartRejected() = workouts.dismissStartRejected()

    fun pauseWorkout() = workouts.pause()
    fun resumeWorkout() = workouts.resume()

    /** Stops the belt, if it is a pad walk, and saves the workout. */
    fun endWorkout() = workouts.end()
    fun dismissSummary() = workouts.dismissSummary()
    fun dismissTooShort() = workouts.dismissTooShort()

    /** Removes the last walk from the health store and from this device. */
    fun deleteSummary() = workouts.deleteSummary()
    fun connectHealth() = workouts.requestHealthAccess()
    fun dismissHealthCard() = workouts.declineHealthAccess()
    fun dismissWatchTip() = workouts.dismissWatchTip()
    fun retrySync() = workouts.retryPendingSync()

    /** Sets the weight used for calories when the health store has none; null clears it. */
    fun setWeight(kg: Double?) = workouts.setManualWeight(kg)

    /** Sets the birth year and sex for heart-rate calories and zones where the health store has none. */
    fun setProfile(birthYear: Int?, sex: Sex?) = workouts.setManualProfile(birthYear, sex)

    /** Call when the app comes to the foreground: walks saved while the phone was locked go out now. */
    fun onForeground() = workouts.retryPendingSync()

    /** Swift-friendly observation of [uiState]; call `close()` on the result to stop. */
    fun watch(onChange: (WorkoutUiState) -> Unit): AutoCloseable {
        val job = viewModelScope.launch { uiState.collect { onChange(it) } }
        return AutoCloseable { job.cancel() }
    }

    private fun WorkoutStatus.toUi(connected: Boolean, paceAt: PadCounters): WorkoutUiState {
        val authorized = health.authorization == HealthAuthorization.Granted
        return WorkoutUiState(
            phase = phase,
            kind = kind,
            usesPad = source == WorkoutSource.Pad,
            starting = starting,
            startRejected = startRejected,
            pauseReason = pauseReason,
            pausedSeconds = pausedSeconds,
            durationSeconds = totals.seconds,
            distanceMeters = totals.meters,
            steps = totals.steps,
            activeKcal = activeKcal.roundToInt(),
            paceSecondsPerKm = pace(paceAt.seconds, paceAt.meters),
            route = route,
            course = course,
            navigation = navigation,
            navigationAlerts = navigationAlerts,
            weightEstimated = weight.source == WeightSource.Default,
            manualWeightKg = manualWeightKg,
            healthWeightKg = weight.kg.takeIf { weight.source == WeightSource.Health },
            manualBirthYear = manualBirthYear,
            manualSex = manualSex,
            ageFromHealth = healthProfile.ageYears != null,
            sexFromHealth = healthProfile.sex != null,
            summary = summary?.toUi(authorized),
            tooShort = tooShort,
            health = HealthUi(
                available = health.availability == HealthAvailability.Available,
                showConnectCard = connected && health.availability == HealthAvailability.Available &&
                    health.prompt == HealthPrompt.NotAsked && health.authorization == HealthAuthorization.NotDetermined,
                accessDenied = health.prompt == HealthPrompt.Declined || health.authorization == HealthAuthorization.Denied,
                showPriorityTip = health.showPriorityTip,
                showInstallCard = connected && health.availability == HealthAvailability.NotInstalled && health.prompt == HealthPrompt.NotAsked,
            ),
        )
    }

    private fun WorkoutRecord.toUi(authorized: Boolean) = WorkoutSummaryUi(
        startEpochMs = startMs,
        endEpochMs = endMs,
        durationSeconds = activeSeconds,
        distanceMeters = distanceMeters,
        steps = steps,
        activeKcal = activeKcal.roundToInt(),
        totalKcal = totalKcal.roundToInt(),
        paceSecondsPerKm = pace(activeSeconds, distanceMeters),
        weightEstimated = weight.source == WeightSource.Default,
        sync = when (val state = syncState) {
            SyncState.Pending -> if (authorized) SyncUi.Saving else SyncUi.WaitingForAccess
            SyncState.Busy -> SyncUi.Busy
            SyncState.Saved -> SyncUi.Saved
            is SyncState.Failed -> SyncUi.Failed
            is SyncState.DeleteFailed -> SyncUi.DeleteFailed
            SyncState.NotAuthorized -> SyncUi.NotSaved
            SyncState.Unavailable -> SyncUi.Unavailable
        },
        syncMessage = when (val state = syncState) {
            is SyncState.Failed -> state.message
            is SyncState.DeleteFailed -> state.message
            else -> null
        },
        avgHeartRate = avgHeartRate,
        maxHeartRate = maxHeartRate,
        energyFromHeartRate = energyFromHeartRate,
        kind = kind,
        route = route,
    )

    private fun pace(seconds: Int, meters: Int): Int? =
        if (meters < MIN_PACE_METERS) null else (seconds * 1000L / meters).toInt()

    companion object {
        private const val MIN_PACE_METERS = 50

        /** How long a walk waits for a lost pad before the screen offers to end it. */
        const val CONNECTION_LOST_SECONDS = 30
    }
}
