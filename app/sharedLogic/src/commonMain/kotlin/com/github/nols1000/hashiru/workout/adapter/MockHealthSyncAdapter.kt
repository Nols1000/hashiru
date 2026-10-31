package com.github.nols1000.hashiru.workout.adapter

import com.github.nols1000.hashiru.workout.domain.HealthEvent
import com.github.nols1000.hashiru.workout.domain.Location
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.RejectReason
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.TrackedTotals
import com.github.nols1000.hashiru.workout.domain.UserProfile
import com.github.nols1000.hashiru.workout.domain.WorkoutEvent
import com.github.nols1000.hashiru.workout.domain.WorkoutRecord
import com.github.nols1000.hashiru.workout.domain.WorkoutSource
import com.github.nols1000.hashiru.workout.port.HealthAuthorization
import com.github.nols1000.hashiru.workout.port.HealthAvailability
import com.github.nols1000.hashiru.workout.port.HealthStoreBusyException
import com.github.nols1000.hashiru.workout.port.HealthSyncPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Health store kept in memory. Grants access when asked unless [grantOnRequest] is false, and fails the next
 * save with [failNextSave] to exercise the retry path, and the next delete with [failNextDelete]. Phone workouts are tracked unless [rejectPhoneWith] is
 * set; with a [scope] their counters advance on their own like a brisk walk, and outdoor ones go round a
 * park, otherwise tests feed them with [progress] and [route].
 */
@OptIn(ExperimentalTime::class)
class MockHealthSyncAdapter(
    var availability: HealthAvailability = HealthAvailability.Available,
    var authorization: HealthAuthorization = HealthAuthorization.NotDetermined,
    var grantOnRequest: Boolean = true,
    var bodyMassKg: Double? = null,
    var profile: UserProfile? = null,
    private val scope: CoroutineScope? = null,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : HealthSyncPort {
    private val _events = MutableSharedFlow<HealthEvent>(extraBufferCapacity = 64)
    override val events: Flow<HealthEvent> = _events

    private val _saved = linkedMapOf<String, WorkoutRecord>()
    val saved: Map<String, WorkoutRecord> get() = _saved

    private val _received = mutableListOf<WorkoutEvent>()
    val received: List<WorkoutEvent> get() = _received

    var failNextSave: Exception? = null
    var failNextDelete: Exception? = null
    var rejectPhoneWith: RejectReason? = null

    private var tracked: String? = null
    private var outdoor = false
    private var counters = PadCounters.Zero
    private var paused = false
    private var ticker: Job? = null

    override fun handle(event: WorkoutEvent) {
        _received += event
        when (event) {
            is WorkoutEvent.StartRequested -> if (event.source == WorkoutSource.Phone) startTracking(event.id, event.kind.location == Location.Outdoor)
            is WorkoutEvent.Restored -> if (event.source == WorkoutSource.Phone) startTracking(event.id, event.kind.location == Location.Outdoor)
            is WorkoutEvent.Paused -> paused = true
            is WorkoutEvent.Resumed -> paused = false
            is WorkoutEvent.Counted -> Unit
            is WorkoutEvent.Discarded -> stopTracking(event.id)
            is WorkoutEvent.Finished -> {
                stopTracking(event.record.id)
                save(event.record)
            }
        }
    }

    /** Reports [counters] for the tracked workout, as the phone would. */
    fun progress(counters: PadCounters) {
        val id = tracked ?: return
        this.counters = counters
        _events.tryEmit(HealthEvent.Progress(id, TrackedTotals(counters.seconds, counters.meters, counters.steps)))
    }

    /** Reports [totals] for the workout [id], as a store that runs a workout session for it would. */
    fun progress(id: String, totals: TrackedTotals) {
        _events.tryEmit(HealthEvent.Progress(id, totals))
    }

    /** Reports [points] on the tracked workout's route, as the phone would outdoors. */
    fun route(points: List<RoutePoint>) {
        val id = tracked ?: return
        _events.tryEmit(HealthEvent.Route(id, points))
    }

    /** Stops tracking as if the system ended it. */
    fun loseTracking() {
        val id = tracked ?: return
        stopTracking(id)
        _events.tryEmit(HealthEvent.TrackingLost(id))
    }

    private fun startTracking(id: String, outdoor: Boolean) {
        rejectPhoneWith?.let {
            _events.tryEmit(HealthEvent.Rejected(id, it))
            return
        }
        tracked = id
        this.outdoor = outdoor
        counters = PadCounters.Zero
        paused = false
        _events.tryEmit(HealthEvent.Tracking(id))
        ticker?.cancel()
        ticker = scope?.launch {
            while (true) {
                delay(1_000)
                if (paused) continue
                progress(PadCounters(counters.seconds + 1, counters.meters + 1 + counters.seconds % 3 / 2, counters.steps + 2))
                if (outdoor) route(listOf(parkPoint(counters.meters)))
            }
        }
    }

    private fun stopTracking(id: String) {
        if (tracked != id) return
        tracked = null
        ticker?.cancel()
        ticker = null
    }

    /** [meters] along a 1 km loop round a park. */
    private fun parkPoint(meters: Int): RoutePoint {
        val angle = 2 * PI * (meters % PARK_LOOP_METERS) / PARK_LOOP_METERS
        val radiusDegrees = PARK_LOOP_METERS / (2 * PI) / METERS_PER_DEGREE
        return RoutePoint(
            atMs = nowMs(),
            latitude = PARK_LATITUDE + radiusDegrees * sin(angle),
            longitude = PARK_LONGITUDE + radiusDegrees * cos(angle) / cos(PARK_LATITUDE * PI / 180),
            altitudeM = 40.0,
            accuracyM = 5.0,
        )
    }

    private fun save(record: WorkoutRecord) {
        val failure = failNextSave
        failNextSave = null
        _events.tryEmit(
            when (failure) {
                null -> {
                    _saved[record.id] = record
                    HealthEvent.Saved(record.id)
                }
                is HealthStoreBusyException -> HealthEvent.SaveDeferred(record.id)
                else -> HealthEvent.SaveFailed(record.id, failure.message ?: "Could not save the workout")
            },
        )
    }

    override fun availability(): HealthAvailability = availability

    override suspend fun authorizationStatus(): HealthAuthorization = authorization

    override suspend fun requestAuthorization(): HealthAuthorization {
        authorization = if (grantOnRequest) HealthAuthorization.Granted else HealthAuthorization.Denied
        return authorization
    }

    override suspend fun latestBodyMassKg(): Double? = bodyMassKg.takeIf { authorization == HealthAuthorization.Granted }

    override suspend fun profile(): UserProfile? = profile.takeIf { authorization == HealthAuthorization.Granted }

    override suspend fun delete(record: WorkoutRecord) {
        failNextDelete?.let {
            failNextDelete = null
            throw it
        }
        _saved.remove(record.id)
    }

    private companion object {
        const val PARK_LOOP_METERS = 1_000
        const val PARK_LATITUDE = 35.6852
        const val PARK_LONGITUDE = 139.7101
        const val METERS_PER_DEGREE = 111_320.0
    }
}
