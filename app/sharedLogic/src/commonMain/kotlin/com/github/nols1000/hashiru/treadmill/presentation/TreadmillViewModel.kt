package com.github.nols1000.hashiru.treadmill.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.nols1000.hashiru.companion.domain.CompanionState
import com.github.nols1000.hashiru.companion.engine.WatchRelay
import com.github.nols1000.hashiru.share.domain.LiveShareStatus
import com.github.nols1000.hashiru.share.engine.LiveShareEngine
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.domain.PairingCancelledException
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.treadmill.domain.TreadmillError
import com.github.nols1000.hashiru.treadmill.domain.TreadmillException
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.HeartRateZone
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.domain.NavigationStatus
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class ShareUiPhase { Off, Live, Ended, Failed }

/** Live sharing on the workout screens, flattened from [LiveShareStatus] for Swift. */
data class ShareUi(
    val phase: ShareUiPhase = ShareUiPhase.Off,
    /** Set while Live and Ended; includes the fragment, which holds the keys. */
    val link: String? = null,
    val watchers: Int = 0,
    val offline: Boolean = false,
    val includeHeartRate: Boolean = false,
    /** The persisted default for the heart-rate switch in the share dialog. */
    val heartRateByDefault: Boolean = false,
)

private fun LiveShareStatus.toUi(heartRateByDefault: Boolean): ShareUi = when (this) {
    LiveShareStatus.Off -> ShareUi(heartRateByDefault = heartRateByDefault)
    is LiveShareStatus.Live -> ShareUi(
        phase = ShareUiPhase.Live,
        link = link,
        watchers = watchers,
        offline = offline,
        includeHeartRate = includeHeartRate,
        heartRateByDefault = heartRateByDefault,
    )
    is LiveShareStatus.Ended -> ShareUi(phase = ShareUiPhase.Ended, link = link, heartRateByDefault = heartRateByDefault)
    LiveShareStatus.Failed -> ShareUi(phase = ShareUiPhase.Failed, heartRateByDefault = heartRateByDefault)
}

data class TreadmillUiState(
    val isConnected: Boolean = false,
    val speedKmh: Double = 0.0,
    val isRunning: Boolean = false,
    val isConnecting: Boolean = false,
    val error: TreadmillError? = null,
    val elapsedSeconds: Int = 0,
    val distanceMeters: Int = 0,
    val steps: Int = 0,
    val isPaired: Boolean = false,
    /** False on OS versions without system pairing; the UI then offers a plain scan-and-connect. */
    val canPair: Boolean = true,
    val workout: WorkoutUiState = WorkoutUiState(),
    val share: ShareUi = ShareUi(),
) {
    private val speedTenths: Int get() = (speedKmh * 10).roundToInt()
    val canIncreaseSpeed: Boolean get() = isRunning && speedTenths < TreadmillViewModel.MAX_SPEED_TENTHS
    val canDecreaseSpeed: Boolean get() = isRunning && speedTenths > TreadmillViewModel.MIN_SPEED_TENTHS
}

/** The state before anything is known; for Swift, which cannot use the constructor's default arguments. */
fun initialTreadmillUiState(): TreadmillUiState = TreadmillUiState()

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
        get() = usesPad && pauseReason == PauseReason.Disconnected && pausedSeconds >= TreadmillViewModel.CONNECTION_LOST_SECONDS
}

/** [Busy]: the health store cannot be written right now (phone locked); the walk is saved on the next foreground. */
enum class SyncUi { Saving, WaitingForAccess, Busy, Saved, Failed, NotSaved, Unavailable }

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
 * Presents a [TreadmillSession]. The session outlives this ViewModel, so clearing it leaves the connection up;
 * it and the components beside it are started by whoever built them.
 */
class TreadmillViewModel(
    private val session: TreadmillSession,
    private val workouts: WorkoutRecorder,
    private val relay: WatchRelay,
    /** Null on hosts that do not configure live sharing. */
    private val share: LiveShareEngine? = null,
) : ViewModel() {
    private class Walk(
        val status: WorkoutStatus,
        val heart: HeartRateSample?,
        val companion: CompanionState,
        val share: LiveShareStatus,
        val heartRateByDefault: Boolean,
    ) {
        /** The health store's reading where it measures heart rate, else the watch's own message. */
        val bpm: Int? get() = status.heartRateBpm ?: heart?.bpm
    }

    private val error = MutableStateFlow<TreadmillError?>(null)
    private val shareStatus = share?.status ?: NO_SHARE_STATUS
    private val shareHeartRateByDefault = share?.shareHeartRateByDefault ?: NO_SHARE_HEART_RATE

    /** Last speed requested via the stepper, so rapid taps build on each other before telemetry catches up. */
    private var requestedTenths: Int? = null

    /**
     * The walk's time and distance when the distance last changed. The pad counts distance in 10 m steps, so
     * only then do the two agree; a pace from them does not drift between steps.
     */
    private var paceAt = PadCounters.Zero

    private val walk = combine(
        workouts.status,
        relay.heartRate,
        relay.companion,
        shareStatus,
        shareHeartRateByDefault,
        ::Walk,
    )

    val uiState: StateFlow<TreadmillUiState> =
        combine(session.connectionState, session.telemetry, session.association, error, walk) { connection, telemetry, association, error, walk ->
            val connected = connection == ConnectionState.Connected
            TreadmillUiState(
                isConnected = connected,
                speedKmh = if (connected) telemetry.speedKmh else 0.0,
                isRunning = connected && telemetry.isRunning,
                isConnecting = connection == ConnectionState.Connecting,
                error = error,
                elapsedSeconds = if (connected) telemetry.elapsedSeconds else 0,
                distanceMeters = if (connected) telemetry.distanceMeters else 0,
                steps = if (connected) telemetry.steps else 0,
                isPaired = association != null,
                canPair = session.canPair,
                workout = walk.status.toUi(connected).copy(
                    heartRateBpm = walk.bpm,
                    heartRateZone = walk.bpm?.let { HeartRateZone.of(it, walk.status.profile.ageYears) },
                    companion = walk.companion,
                    showWatchTip = connected && walk.companion == CompanionState.AppNotInstalled && !walk.status.watchTipDismissed,
                ),
                share = walk.share.toUi(walk.heartRateByDefault),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, TreadmillUiState())

    init {
        viewModelScope.launch {
            session.telemetry.collect {
                val tenths = (it.speedKmh * 10).roundToInt()
                if (!it.isRunning || tenths == requestedTenths) requestedTenths = null
                // Stopped another way, by the pad's button or remote: the failed stop no longer matters.
                if (!it.isRunning && error.value == TreadmillError.CommandFailed(TreadmillCommand.StopBelt)) error.value = null
            }
        }
    }

    fun connect() {
        if (session.connectionState.value != ConnectionState.Disconnected) return
        error.value = null
        launchReporting({ TreadmillError.ConnectionFailed() }) { session.connect() }
    }

    /** Shows the system device picker and connects to the chosen treadmill. Dismissing the picker is not an error. */
    fun pair() {
        error.value = null
        launchReporting({ TreadmillError.ConnectionFailed() }) {
            try {
                session.pair()
            } catch (_: PairingCancelledException) {
            }
        }
    }

    /** Disconnects and removes the stored association. */
    fun forget() {
        requestedTenths = null
        launchReporting({ TreadmillError.ConnectionFailed() }) { session.forget() }
    }

    fun disconnect() {
        session.disconnect()
    }

    fun toggleBelt() {
        val command = if (uiState.value.isRunning) TreadmillCommand.StopBelt else TreadmillCommand.StartBelt
        requestedTenths = null
        dispatch(command)
    }

    fun stopBelt() {
        requestedTenths = null
        dispatch(TreadmillCommand.StopBelt)
    }

    /** Raises the speed to the next [SPEED_STEP_TENTHS] step, up to [MAX_SPEED_TENTHS]. */
    fun increaseSpeed() {
        if (!uiState.value.isRunning) return
        val base = requestedTenths ?: currentTenths()
        stepTo((base / SPEED_STEP_TENTHS + 1) * SPEED_STEP_TENTHS)
    }

    /** Lowers the speed to the previous [SPEED_STEP_TENTHS] step, down to [MIN_SPEED_TENTHS]. */
    fun decreaseSpeed() {
        if (!uiState.value.isRunning) return
        val base = requestedTenths ?: currentTenths()
        stepTo((base - 1) / SPEED_STEP_TENTHS * SPEED_STEP_TENTHS)
    }

    fun dismissError() {
        error.value = null
    }

    /** Starts a phone workout of [kind], following the course [courseId] where there is one; with the pad, starting the belt starts the walk instead. */
    fun startWorkout(kind: WorkoutKind, courseId: String?) = workouts.start(kind, courseId)
    fun dismissStartRejected() = workouts.dismissStartRejected()

    fun pauseWorkout() = workouts.pause()
    fun resumeWorkout() = workouts.resume()
    fun startShare(includeHeartRate: Boolean) = requiredShare().share(includeHeartRate)
    fun stopShare() = requiredShare().stopSharing()
    fun dismissShareEnded() = requiredShare().dismissEnded()

    private fun requiredShare() = checkNotNull(share) { "Live sharing is not configured on this platform" }

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

    private fun currentTenths() = (uiState.value.speedKmh * 10).roundToInt()

    private fun stepTo(tenths: Int) {
        val target = tenths.coerceIn(MIN_SPEED_TENTHS, MAX_SPEED_TENTHS)
        if (target == (requestedTenths ?: currentTenths())) return
        requestedTenths = target
        dispatch(TreadmillCommand.SetSpeed(target))
    }

    private fun dispatch(command: TreadmillCommand) {
        launchReporting({ TreadmillError.CommandFailed(command) }) { session.dispatch(command) }
    }

    /** Runs [block], showing a [TreadmillException]'s error, or [fallback] for anything else. The message is only logged. */
    private fun launchReporting(fallback: () -> TreadmillError, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("TreadmillViewModel: ${e.message}")
                error.value = (e as? TreadmillException)?.error?.takeUnless { it is TreadmillError.CommandFailed } ?: fallback()
            }
        }
    }

    /** Swift-friendly observation of [uiState]; call `close()` on the result to stop. */
    fun watch(onChange: (TreadmillUiState) -> Unit): AutoCloseable {
        val job = viewModelScope.launch { uiState.collect { onChange(it) } }
        return AutoCloseable { job.cancel() }
    }

    private fun WorkoutStatus.toUi(connected: Boolean): WorkoutUiState {
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
            paceSecondsPerKm = livePace(totals),
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
            SyncState.NotAuthorized -> SyncUi.NotSaved
            SyncState.Unavailable -> SyncUi.Unavailable
        },
        syncMessage = (syncState as? SyncState.Failed)?.message,
        avgHeartRate = avgHeartRate,
        maxHeartRate = maxHeartRate,
        energyFromHeartRate = energyFromHeartRate,
        kind = kind,
        route = route,
    )

    private fun livePace(totals: PadCounters): Int? {
        if (totals.meters != paceAt.meters || totals.seconds < paceAt.seconds) paceAt = totals
        return pace(paceAt.seconds, paceAt.meters)
    }

    private fun pace(seconds: Int, meters: Int): Int? =
        if (meters < MIN_PACE_METERS) null else (seconds * 1000L / meters).toInt()

    companion object {
        private val NO_SHARE_STATUS = MutableStateFlow<LiveShareStatus>(LiveShareStatus.Off)
        private val NO_SHARE_HEART_RATE = MutableStateFlow(false)

        private const val MIN_PACE_METERS = 50

        const val MIN_SPEED_TENTHS = TreadmillCommand.SetSpeed.MIN_TENTHS
        const val MAX_SPEED_TENTHS = TreadmillCommand.SetSpeed.MAX_TENTHS
        const val SPEED_STEP_TENTHS = 5

        /** How long a walk waits for a lost pad before the screen offers to end it. */
        const val CONNECTION_LOST_SECONDS = 30
    }
}
