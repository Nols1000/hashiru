package com.github.nols1000.hashiru.workout.adapter

import com.github.nols1000.hashiru.workout.domain.HealthEvent
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.RejectReason
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

/**
 * Health store kept in memory. Grants access when asked unless [grantOnRequest] is false, and fails the next
 * save with [failNextSave] to exercise the retry path. Phone workouts are tracked unless [rejectPhoneWith] is
 * set; with a [scope] their counters advance on their own like a brisk walk, otherwise tests feed them with
 * [progress].
 */
class MockHealthSyncAdapter(
    var availability: HealthAvailability = HealthAvailability.Available,
    var authorization: HealthAuthorization = HealthAuthorization.NotDetermined,
    var grantOnRequest: Boolean = true,
    var bodyMassKg: Double? = null,
    var profile: UserProfile? = null,
    private val scope: CoroutineScope? = null,
) : HealthSyncPort {
    private val _events = MutableSharedFlow<HealthEvent>(extraBufferCapacity = 64)
    override val events: Flow<HealthEvent> = _events

    private val _saved = linkedMapOf<String, WorkoutRecord>()
    val saved: Map<String, WorkoutRecord> get() = _saved

    private val _received = mutableListOf<WorkoutEvent>()
    val received: List<WorkoutEvent> get() = _received

    var failNextSave: Exception? = null
    var rejectPhoneWith: RejectReason? = null

    private var tracked: String? = null
    private var counters = PadCounters.Zero
    private var paused = false
    private var ticker: Job? = null

    override fun handle(event: WorkoutEvent) {
        _received += event
        when (event) {
            is WorkoutEvent.StartRequested -> if (event.source == WorkoutSource.Phone) startTracking(event.id)
            is WorkoutEvent.Restored -> if (event.source == WorkoutSource.Phone) startTracking(event.id)
            is WorkoutEvent.Paused -> paused = true
            is WorkoutEvent.Resumed -> paused = false
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
        _events.tryEmit(HealthEvent.Progress(id, counters))
    }

    /** Stops tracking as if the system ended it. */
    fun loseTracking() {
        val id = tracked ?: return
        stopTracking(id)
        _events.tryEmit(HealthEvent.TrackingLost(id))
    }

    private fun startTracking(id: String) {
        rejectPhoneWith?.let {
            _events.tryEmit(HealthEvent.Rejected(id, it))
            return
        }
        tracked = id
        counters = PadCounters.Zero
        paused = false
        _events.tryEmit(HealthEvent.Tracking(id))
        ticker?.cancel()
        ticker = scope?.launch {
            while (true) {
                delay(1_000)
                if (!paused) progress(PadCounters(counters.seconds + 1, counters.meters + 1 + counters.seconds % 3 / 2, counters.steps + 2))
            }
        }
    }

    private fun stopTracking(id: String) {
        if (tracked != id) return
        tracked = null
        ticker?.cancel()
        ticker = null
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
        _saved.remove(record.id)
    }
}
