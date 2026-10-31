package com.github.nols1000.hashiru.workout.engine

import com.github.nols1000.hashiru.course.adapter.InMemoryCourseLibrary
import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.course.domain.NavigationStatus
import com.github.nols1000.hashiru.course.engine.CourseNavigator
import com.github.nols1000.hashiru.course.port.CourseLibraryPort
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.engine.TreadmillSession
import com.github.nols1000.hashiru.workout.domain.BodyWeight
import com.github.nols1000.hashiru.workout.domain.DiscardReason
import com.github.nols1000.hashiru.workout.domain.HealthEvent
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.PauseReason
import com.github.nols1000.hashiru.workout.domain.RejectReason
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.Sex
import com.github.nols1000.hashiru.workout.domain.SyncState
import com.github.nols1000.hashiru.workout.domain.UserProfile
import com.github.nols1000.hashiru.workout.domain.WeightSource
import com.github.nols1000.hashiru.workout.domain.WorkoutEvent
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.domain.WorkoutRecord
import com.github.nols1000.hashiru.workout.domain.WorkoutSource
import com.github.nols1000.hashiru.workout.port.HealthAuthorization
import com.github.nols1000.hashiru.workout.port.HealthAvailability
import com.github.nols1000.hashiru.workout.port.HealthStoreBusyException
import com.github.nols1000.hashiru.workout.port.HealthSyncPort
import com.github.nols1000.hashiru.workout.port.KeyValueStorePort
import com.github.nols1000.hashiru.workout.store.HealthPrompt
import com.github.nols1000.hashiru.workout.store.WorkoutStore
import com.github.nols1000.hashiru.workout.store.needsSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class WorkoutPhase { Idle, Active, Paused }

data class HealthStatus(
    val availability: HealthAvailability = HealthAvailability.NotSupported,
    val authorization: HealthAuthorization = HealthAuthorization.NotDetermined,
    val prompt: HealthPrompt = HealthPrompt.NotAsked,
    /** Shown once, on the first summary that was saved, to explain data-source priority. */
    val showPriorityTip: Boolean = false,
)

/**
 * What the UI shows about workouts. [totals] and [activeKcal] cover the workout in progress, across pauses, as
 * the health store measures them where it does, else as the walk's source counted them.
 */
data class WorkoutStatus(
    val phase: WorkoutPhase = WorkoutPhase.Idle,
    /** The workout in progress, or the last one asked for while waiting for it to start. */
    val kind: WorkoutKind = WorkoutKind.IndoorWalk,
    val source: WorkoutSource = WorkoutSource.Pad,
    /** A phone workout was asked for and the health store has not started it yet. */
    val starting: Boolean = false,
    /** Why the health store would not start the last workout asked for, until the user dismisses it. */
    val startRejected: RejectReason? = null,
    val pauseReason: PauseReason? = null,
    val pausedSeconds: Int = 0,
    val totals: PadCounters = PadCounters.Zero,
    val activeKcal: Double = 0.0,
    /** The latest heart rate as the health store measured it; null where it measures none. */
    val heartRateBpm: Int? = null,
    /** Where the outdoor workout in progress went so far. */
    val route: List<RoutePoint> = emptyList(),
    /** The course the workout in progress follows, and where it stands on it. */
    val course: Course? = null,
    val navigation: NavigationStatus? = null,
    /** Goes up each time the workout strays from its course or comes back to it, so the UI can alert once. */
    val navigationAlerts: Int = 0,
    val weight: BodyWeight = BodyWeight.Default,
    val manualWeightKg: Double? = null,
    /** Age and sex for heart-rate energy and zones: from the health store where it has them, else manual. */
    val profile: UserProfile = UserProfile(),
    /** The part of [profile] the health store provides; settings hide what it covers. */
    val healthProfile: UserProfile = UserProfile(),
    val manualBirthYear: Int? = null,
    val manualSex: Sex? = null,
    /** The last finished walk, until the user dismisses it. */
    val summary: WorkoutRecord? = null,
    /** The last walk ended under the minimums and was not saved, until the user dismisses it or walks again. */
    val tooShort: Boolean = false,
    val health: HealthStatus = HealthStatus(),
    val watchTipDismissed: Boolean = false,
)

/**
 * App-scoped recorder of workouts. Like [TreadmillSession] it outlives every screen: it feeds the session's
 * telemetry and the health store's answers into [WorkoutStateMachine], checkpoints the workout in progress so it
 * survives process death, keeps the health store informed, and has it save finished workouts, retrying those
 * that could not be saved yet.
 */
class WorkoutRecorder internal constructor(
    private val session: TreadmillSession,
    private val health: HealthSyncPort,
    private val store: WorkoutStore,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val newId: () -> String,
    private val courses: CourseLibraryPort = InMemoryCourseLibrary(),
) {
    /** Creates the recorder on the main thread for the rest of the process; keep a single instance. */
    @OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
    constructor(
        session: TreadmillSession,
        health: HealthSyncPort,
        storage: KeyValueStorePort,
        courses: CourseLibraryPort = InMemoryCourseLibrary(),
    ) : this(
        session,
        health,
        WorkoutStore(storage),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        { Clock.System.now().toEpochMilliseconds() },
        { Uuid.random().toString() },
        courses,
    )

    private val _status = MutableStateFlow(WorkoutStatus())
    val status: StateFlow<WorkoutStatus> = _status.asStateFlow()

    private var machine: WorkoutState = WorkoutState.Idle()
    private var weight = BodyWeight.Default
    private var healthProfile = UserProfile()
    private var profile = UserProfile()
    private var summary: WorkoutRecord? = null
    private var tooShort = false
    private var authorization = HealthAuthorization.NotDetermined
    private var startRejected: RejectReason? = null
    private var started = false
    private var navigator: CourseNavigator? = null
    private var course: Course? = null
    private var navigationAlerts = 0

    fun start() {
        if (started) return
        started = true
        session.start()
        // Listening before anything is sent, so no answer is missed.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { health.events.collect(::onHealthEvent) }
        machine = store.inProgress?.let { WorkoutStateMachine.restore(it, nowMs()) } ?: WorkoutState.Idle(store.lastFinalCounters)
        (machine as? WorkoutState.InProgress)?.let { health.handle(WorkoutEvent.Restored(it.id, it.startMs, it.kind, it.source)) }
        summary = store.lastSummaryId?.let(store::record)
        publish()
        // A workout restored after process death may have timed out meanwhile.
        apply(WorkoutInput.Tick)

        scope.launch {
            session.connectionState.collect { if (it != ConnectionState.Connected) apply(WorkoutInput.ConnectionLost) }
        }
        scope.launch {
            session.telemetry.collect {
                if (session.connectionState.value == ConnectionState.Connected) apply(WorkoutInput.Telemetry(it))
            }
        }
        scope.launch {
            status.map { it.phase != WorkoutPhase.Idle || it.starting }.distinctUntilChanged().collectLatest { inProgress ->
                while (inProgress) {
                    delay(TICK_MS)
                    apply(WorkoutInput.Tick)
                }
            }
        }
        scope.launch {
            refreshHealth()
            syncPending()
        }
    }

    /** Starts a workout of [kind] that the phone tracks, once the health store confirms it. */
    /** Starts a workout of [kind] that the phone tracks, once the health store confirms it, following [courseId]. */
    fun start(kind: WorkoutKind, courseId: String? = null) {
        startRejected = null
        apply(WorkoutInput.UserStart(kind, courseId))
    }

    fun dismissStartRejected() {
        startRejected = null
        publish()
    }

    fun pause() = apply(WorkoutInput.UserPause)
    fun resume() = apply(WorkoutInput.UserResume)
    fun end() = apply(WorkoutInput.UserEnd)
    fun discard() = apply(WorkoutInput.UserDiscard)

    /** Feeds a reading from the watch into the walk in progress; ignored while idle or paused. */
    fun recordHeartRate(sample: HeartRateSample) = apply(WorkoutInput.HeartRate(sample))

    /** Retries walks that could not be saved yet; call when the app comes to the foreground. */
    fun retryPendingSync() {
        scope.launch {
            refreshHealth()
            syncPending()
        }
    }

    /** Shows the system's health permission sheet, then saves the walks that were waiting for it. */
    fun requestHealthAccess() {
        scope.launch {
            authorization = report { health.requestAuthorization() } ?: authorization
            store.healthPrompt = HealthPrompt.Accepted
            refreshHealth()
            syncPending()
        }
    }

    /** Keeps walks on this device only; the user can still connect later from settings. */
    fun declineHealthAccess() {
        store.healthPrompt = HealthPrompt.Declined
        store.records().filter { it.syncState == SyncState.Pending }.forEach { update(it.copy(syncState = SyncState.NotAuthorized)) }
        publish()
    }

    fun dismissTooShort() {
        tooShort = false
        publish()
    }

    /** Stops offering the watch app. */
    fun dismissWatchTip() {
        store.watchTipDismissed = true
        publish()
    }

    fun setManualWeight(kg: Double?) {
        store.manualWeightKg = kg?.takeIf { it in MIN_WEIGHT_KG..MAX_WEIGHT_KG }
        scope.launch { refreshHealth() }
    }

    /** Sets the birth year and sex used where the health store has none; null clears either. */
    fun setManualProfile(birthYear: Int?, sex: Sex?) {
        val year = yearOf(nowMs())
        store.manualBirthYear = birthYear?.takeIf { it in MIN_BIRTH_YEAR..year - MIN_AGE_YEARS }
        store.manualSex = sex
        scope.launch { refreshHealth() }
    }

    fun dismissSummary() {
        val record = summary ?: return
        if (record.syncState == SyncState.Saved) store.priorityTipShown = true
        summary = null
        store.lastSummaryId = null
        if (!record.needsSync) store.remove(record.id)
        publish()
    }

    /**
     * Removes the last walk from the health store and from this device. Should the store fail to delete it, the
     * walk stays, marked as not saved: the store may still hold all or part of it, so the next retry writes it
     * again in full, and the user can delete it once more.
     */
    fun deleteSummary() {
        val record = summary ?: return
        scope.launch {
            if (record.syncState == SyncState.Saved) {
                val failed = try {
                    health.delete(record)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HealthStoreBusyException) {
                    SyncState.Busy
                } catch (e: Exception) {
                    SyncState.Failed(e.message ?: "Could not delete the workout")
                }
                if (failed != null) {
                    updateSync(record.id, failed)
                    return@launch
                }
            }
            // Also forgets the summary, so it does not come back on the next launch.
            store.remove(record.id)
            if (summary?.id == record.id) summary = null
            publish()
        }
    }

    private fun apply(input: WorkoutInput) {
        val transition = WorkoutStateMachine.reduce(machine, input, WorkoutContext(nowMs(), weight, profile, newId))
        machine = transition.state
        for (effect in transition.effects) {
            when (effect) {
                is WorkoutEffect.Send -> scope.launch { report { session.dispatch(effect.command) } }
                is WorkoutEffect.Notify -> health.handle(effect.event)
                WorkoutEffect.Checkpoint -> store.inProgress = machine as? WorkoutState.InProgress
                is WorkoutEffect.Completed -> {
                    finishWalk()
                    val record = effect.record
                    store.lastSummaryId = record.id
                    store.put(record)
                    summary = record
                    sync(record)
                }
                is WorkoutEffect.Discarded -> {
                    finishWalk()
                    tooShort = effect.reason == DiscardReason.TooShort
                }
            }
        }
        if (machine is WorkoutState.InProgress) tooShort = false
        followCourse()
        publish()
    }

    /**
     * Keeps the navigator in step with the workout: made when a workout with a course begins or comes back after
     * process death, then caught up on the route so far, and dropped when it ends.
     */
    private fun followCourse() {
        val state = machine as? WorkoutState.InProgress
        val id = state?.courseId
        if (id == null) {
            navigator = null
            course = null
            return
        }
        if (course?.id == id) return
        val loaded = courses.load(id)
        course = loaded
        navigator = loaded?.let(::CourseNavigator)?.also { n -> state.route.forEach { n.update(it.latitude, it.longitude, it.accuracyM) } }
    }

    private fun finishWalk() {
        store.inProgress = null
        store.lastFinalCounters = (machine as? WorkoutState.Idle)?.lastFinal
    }

    private fun onHealthEvent(event: HealthEvent) {
        when (event) {
            is HealthEvent.Tracking -> apply(WorkoutInput.TrackingStarted(event.id))
            is HealthEvent.Rejected -> {
                if ((machine as? WorkoutState.Idle)?.pendingStart?.id == event.id) startRejected = event.reason
                apply(WorkoutInput.TrackingRejected(event.id))
            }
            is HealthEvent.Progress -> apply(WorkoutInput.Progress(event.id, event.totals))
            is HealthEvent.TrackingLost -> apply(WorkoutInput.TrackingLost(event.id))
            is HealthEvent.Route -> {
                val before = (machine as? WorkoutState.InProgress)?.route?.size ?: 0
                val following = navigator
                apply(WorkoutInput.Route(event.id, event.points))
                // A navigator just made has replayed the route already.
                val navigator = navigator?.takeIf { it === following } ?: return
                val added = (machine as? WorkoutState.InProgress)?.route?.drop(before).orEmpty()
                if (added.isEmpty()) return
                for (point in added) {
                    val wasOnCourse = navigator.status.onCourse
                    if (navigator.update(point.latitude, point.longitude, point.accuracyM).onCourse != wasOnCourse) navigationAlerts++
                }
                publish()
            }
            is HealthEvent.Saved -> updateSync(event.id, SyncState.Saved)
            is HealthEvent.SaveDeferred -> updateSync(event.id, SyncState.Busy)
            is HealthEvent.SaveFailed -> updateSync(event.id, SyncState.Failed(event.message))
        }
    }

    private fun updateSync(id: String, state: SyncState) {
        val record = store.record(id) ?: return
        update(record.copy(syncState = state))
    }

    private fun syncPending() {
        store.records().filter { it.needsSync }.forEach(::sync)
    }

    /** Has the health store save [record] where it may; the answer comes as a [HealthEvent]. */
    private fun sync(record: WorkoutRecord) {
        val current = store.record(record.id) ?: record
        if (!current.needsSync) return
        val held = when {
            health.availability() != HealthAvailability.Available -> SyncState.Unavailable
            store.healthPrompt == HealthPrompt.Declined -> SyncState.NotAuthorized
            authorization == HealthAuthorization.Denied -> SyncState.NotAuthorized
            authorization == HealthAuthorization.NotDetermined -> SyncState.Pending
            else -> null
        }
        if (held != null) {
            if (held != current.syncState) update(current.copy(syncState = held))
        } else {
            health.handle(WorkoutEvent.Finished(current))
        }
    }

    private fun update(record: WorkoutRecord) {
        store.put(record)
        if (summary?.id == record.id) summary = record
        publish()
    }

    private suspend fun refreshHealth() {
        if (health.availability() == HealthAvailability.Available) {
            authorization = report { health.authorizationStatus() } ?: authorization
        }
        val healthKg = if (authorization == HealthAuthorization.Granted) report { health.latestBodyMassKg() } else null
        val manualKg = store.manualWeightKg
        weight = when {
            healthKg != null -> BodyWeight(healthKg, WeightSource.Health)
            manualKg != null -> BodyWeight(manualKg, WeightSource.Manual)
            else -> BodyWeight.Default
        }
        healthProfile = (if (authorization == HealthAuthorization.Granted) report { health.profile() } else null) ?: UserProfile()
        val manualAge = store.manualBirthYear?.let { yearOf(nowMs()) - it }
        profile = healthProfile.orElse(UserProfile(manualAge, store.manualSex))
        publish()
    }

    private fun publish() {
        val healthStatus = HealthStatus(
            availability = health.availability(),
            authorization = authorization,
            prompt = store.healthPrompt,
            showPriorityTip = summary?.syncState == SyncState.Saved && !store.priorityTipShown,
        )
        _status.value = when (val state = machine) {
            is WorkoutState.Idle -> WorkoutStatus(
                kind = state.pendingStart?.kind ?: WorkoutKind.IndoorWalk,
                source = if (state.pendingStart != null) WorkoutSource.Phone else WorkoutSource.Pad,
                starting = state.pendingStart != null,
                startRejected = startRejected,
                weight = weight,
                manualWeightKg = store.manualWeightKg,
                profile = profile,
                healthProfile = healthProfile,
                manualBirthYear = store.manualBirthYear,
                manualSex = store.manualSex,
                summary = summary,
                tooShort = tooShort,
                health = healthStatus,
                watchTipDismissed = store.watchTipDismissed,
            )
            is WorkoutState.InProgress -> {
                val totals = PadCounters(state.activeSeconds, state.meters, state.steps)
                WorkoutStatus(
                    phase = if (state.pause == null) WorkoutPhase.Active else WorkoutPhase.Paused,
                    kind = state.kind,
                    source = state.source,
                    pauseReason = state.pause?.reason,
                    pausedSeconds = state.pause?.let { ((nowMs() - it.sinceMs) / 1000).toInt().coerceAtLeast(0) } ?: 0,
                    totals = totals,
                    activeKcal = state.activeKcal,
                    heartRateBpm = state.tracked?.heartRate?.latestBpm,
                    route = state.route,
                    course = course,
                    navigation = navigator?.status,
                    navigationAlerts = navigationAlerts,
                    weight = state.weight,
                    manualWeightKg = store.manualWeightKg,
                    profile = profile,
                    healthProfile = healthProfile,
                    manualBirthYear = store.manualBirthYear,
                    manualSex = store.manualSex,
                    summary = summary,
                    health = healthStatus,
                    watchTipDismissed = store.watchTipDismissed,
                )
            }
        }
    }

    /** Runs [block], logging instead of throwing; the recorder must keep running whatever the store does. */
    private suspend fun <T> report(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("WorkoutRecorder: ${e.message}")
        null
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MIN_WEIGHT_KG = 20.0
        const val MAX_WEIGHT_KG = 300.0
        const val MIN_BIRTH_YEAR = 1900
        const val MIN_AGE_YEARS = 10

        /** The UTC calendar year of [epochMs] (days-to-civil from Howard Hinnant's date algorithms). */
        fun yearOf(epochMs: Long): Int {
            val z = epochMs.floorDiv(86_400_000L) + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val month = (5 * doy + 2) / 153
            return (yoe + era * 400 + if (month >= 10) 1 else 0).toInt()
        }
    }
}
