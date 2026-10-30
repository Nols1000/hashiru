package com.github.nols1000.hashiru.workout.adapter

import com.github.nols1000.hashiru.workout.domain.HealthEvent
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.RejectReason
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreMotion.CMAuthorizationStatusDenied
import platform.CoreMotion.CMAuthorizationStatusRestricted
import platform.CoreMotion.CMPedometer
import platform.CoreMotion.CMPedometerData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKLiveWorkoutBuilder
import platform.HealthKit.HKLiveWorkoutBuilderDelegateProtocol
import platform.HealthKit.HKLiveWorkoutDataSource
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKSample
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkoutConfiguration
import platform.HealthKit.HKWorkoutEvent
import platform.HealthKit.HKWorkoutSession
import platform.HealthKit.HKWorkoutSessionDelegateProtocol
import platform.HealthKit.HKWorkoutSessionState
import platform.HealthKit.HKWorkoutSessionStateEnded
import platform.HealthKit.countUnit
import platform.HealthKit.meterUnit
import platform.HealthKit.recoverActiveWorkoutSessionWithCompletion
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Tracks one workout on the phone. All calls come on the main thread. */
internal interface PhoneTracker {
    val id: String

    fun pause(atMs: Long)
    fun resume(atMs: Long)

    /** Stops tracking; nothing it tracked is kept. */
    fun stop()
}

internal sealed interface TrackerStart {
    data class Started(val tracker: PhoneTracker) : TrackerStart
    data class Rejected(val reason: RejectReason) : TrackerStart

    /** This way of tracking did not work out; another may. */
    data object Failed : TrackerStart
}

internal fun date(epochMs: Long): NSDate = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)

internal fun NSDate.epochMs() = (timeIntervalSince1970 * 1000).toLong()

@OptIn(ExperimentalTime::class)
internal fun nowMs() = Clock.System.now().toEpochMilliseconds()

/**
 * Reports the counters every second while active, so the duration moves on even without new steps. Counters
 * never go down: the recorder would take that for a reset.
 */
internal abstract class CountingTracker(
    final override val id: String,
    private val emit: (HealthEvent) -> Unit,
    private val scope: CoroutineScope,
) : PhoneTracker {
    private var ticker: Job? = null
    private var last = PadCounters.Zero

    /** The counters now, or null while they cannot be told yet. */
    protected abstract fun counters(): PadCounters?

    protected fun startReporting() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                report()
                delay(TICK_MS)
            }
        }
    }

    protected fun stopReporting() {
        ticker?.cancel()
        ticker = null
    }

    protected fun report() {
        val now = counters() ?: return
        last = PadCounters(maxOf(last.seconds, now.seconds), maxOf(last.meters, now.meters), maxOf(last.steps, now.steps))
        emit(HealthEvent.Progress(id, last))
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}

/**
 * A workout running as an iPhone [HKWorkoutSession] (iOS 26 and later), whose [HKLiveWorkoutBuilder] also saves
 * it. With [countsMotion] it collects steps and distance from the phone's motion sensors and reports them;
 * otherwise the pad counts and the session only gives the save a live builder. Energy is always left to the
 * recorder's model, so it is the same whichever way a workout was tracked.
 */
@OptIn(ExperimentalForeignApi::class)
internal class LiveWorkout private constructor(
    id: String,
    private val session: HKWorkoutSession,
    private val countsMotion: Boolean,
    private val types: HealthTypes,
    private val emit: (HealthEvent) -> Unit,
    private val scope: CoroutineScope,
) : CountingTracker(id, emit, scope) {
    private val builder: HKLiveWorkoutBuilder = session.associatedWorkoutBuilder()
    private val delegate = Delegate()
    private var meters = 0.0
    private var steps = 0.0
    private var finishing = false

    /** False once the system ended the session; its builder can no longer save the workout. */
    var usable = true
        private set

    override fun counters() = PadCounters(builder.elapsedTime.toInt(), meters.roundToInt(), steps.roundToInt())

    override fun pause(atMs: Long) = session.pause()

    override fun resume(atMs: Long) = session.resume()

    override fun stop() {
        finishing = true
        stopReporting()
        session.end()
        builder.discardWorkout()
    }

    /** Ends the session at [endMs] and saves the workout with [samples], [events] and [metadata]; throws if it cannot. */
    suspend fun finish(endMs: Long, samples: List<HKSample>, events: List<HKWorkoutEvent>, metadata: Map<Any?, *>) {
        finishing = true
        stopReporting()
        check(usable) { "The workout session ended early" }
        val end = date(endMs)
        session.stopActivityWithDate(end)
        try {
            await { builder.endCollectionWithEndDate(end) { _, error -> it.complete(error) } }
            if (samples.isNotEmpty()) await { builder.addSamples(samples) { _, error -> it.complete(error) } }
            if (events.isNotEmpty()) await { builder.addWorkoutEvents(events) { _, error -> it.complete(error) } }
            await { builder.addMetadata(metadata) { _, error -> it.complete(error) } }
            await { builder.finishWorkoutWithCompletion { _, error -> it.complete(error) } }
        } finally {
            session.end()
        }
    }

    private fun attach() {
        session.setDelegate(delegate)
        builder.setDelegate(delegate)
        if (countsMotion) startReporting()
    }

    private fun onCollected() {
        meters = builder.statisticsForType(types.distance)?.sumQuantity()?.doubleValueForUnit(HKUnit.meterUnit()) ?: meters
        steps = builder.statisticsForType(types.steps)?.sumQuantity()?.doubleValueForUnit(HKUnit.countUnit()) ?: steps
        if (countsMotion) report()
    }

    private fun onEnded() {
        if (finishing || !usable) return
        usable = false
        stopReporting()
        if (countsMotion) emit(HealthEvent.TrackingLost(id))
    }

    /** HealthKit calls these on its own queues; everything is handled on the main thread. */
    private inner class Delegate : NSObject(), HKWorkoutSessionDelegateProtocol, HKLiveWorkoutBuilderDelegateProtocol {
        override fun workoutSession(
            workoutSession: HKWorkoutSession,
            didChangeToState: HKWorkoutSessionState,
            fromState: HKWorkoutSessionState,
            date: NSDate,
        ) {
            if (didChangeToState == HKWorkoutSessionStateEnded) scope.launch { onEnded() }
        }

        override fun workoutSession(workoutSession: HKWorkoutSession, didFailWithError: NSError) {
            println("LiveWorkout: session failed: ${didFailWithError.localizedDescription}")
            scope.launch { onEnded() }
        }

        override fun workoutBuilder(workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOfTypes: Set<*>) {
            scope.launch { onCollected() }
        }

        override fun workoutBuilderDidCollectEvent(workoutBuilder: HKLiveWorkoutBuilder) {}
    }

    companion object {
        /** Starts a session at [startMs]; [TrackerStart.Failed] when the phone cannot run one for it. */
        suspend fun begin(
            store: HKHealthStore,
            configuration: HKWorkoutConfiguration,
            id: String,
            startMs: Long,
            countsMotion: Boolean,
            types: HealthTypes,
            emit: (HealthEvent) -> Unit,
            scope: CoroutineScope,
        ): TrackerStart {
            val session = try {
                HKWorkoutSession(store, configuration, null)
            } catch (e: Exception) {
                println("LiveWorkout: could not create a session: ${e.message}")
                return TrackerStart.Failed
            }
            val dataSource = HKLiveWorkoutDataSource(store, configuration)
            dataSource.disableCollectionForType(types.energy)
            if (!countsMotion) {
                dataSource.disableCollectionForType(types.distance)
                dataSource.disableCollectionForType(types.steps)
            }
            val workout = LiveWorkout(id, session, countsMotion, types, emit, scope)
            workout.builder.setDataSource(dataSource)
            val start = date(startMs)
            session.startActivityWithDate(start)
            val error = suspendCancellableCoroutine { cont ->
                workout.builder.beginCollectionWithStartDate(start) { _, error -> cont.resume(error) }
            }
            if (error != null) {
                println("LiveWorkout: could not begin: ${error.localizedDescription}")
                session.end()
                return TrackerStart.Failed
            }
            workout.attach()
            return TrackerStart.Started(workout)
        }

        /** Takes back the session that was running when iOS ended the app, if there is one. */
        suspend fun recover(
            store: HKHealthStore,
            id: String,
            countsMotion: Boolean,
            types: HealthTypes,
            emit: (HealthEvent) -> Unit,
            scope: CoroutineScope,
        ): LiveWorkout? {
            val session = suspendCancellableCoroutine<HKWorkoutSession?> { cont ->
                store.recoverActiveWorkoutSessionWithCompletion { session, _ -> cont.resume(session) }
            } ?: return null
            return LiveWorkout(id, session, countsMotion, types, emit, scope).also { it.attach() }
        }
    }
}

/**
 * Counts steps and distance with [CMPedometer], for iPhones without workout sessions (before iOS 26) or where
 * one could not start. The pedometer counts from the start on its own, also while the app is suspended; what it
 * counted during pauses is left out.
 */
@OptIn(ExperimentalForeignApi::class)
internal class PedometerTracker private constructor(
    id: String,
    private val startMs: Long,
    emit: (HealthEvent) -> Unit,
    private val scope: CoroutineScope,
) : CountingTracker(id, emit, scope) {
    private val pedometer = CMPedometer()
    private var sinceStart = PadCounters.Zero
    private var duringPauses = PadCounters.Zero
    private var pausedMs = 0L
    private var pausedAt: Long? = null

    /** Held from a pause until what was counted during it is known. */
    private var held: PadCounters? = null

    override fun counters(): PadCounters {
        held?.let { return it }
        val activeMs = nowMs() - startMs - pausedMs
        return PadCounters((activeMs / 1000).toInt().coerceAtLeast(0), sinceStart.meters - duringPauses.meters, sinceStart.steps - duringPauses.steps)
    }

    override fun pause(atMs: Long) {
        held = counters()
        pausedAt = atMs
    }

    override fun resume(atMs: Long) {
        val from = pausedAt ?: return
        pausedAt = null
        pausedMs += atMs - from
        pedometer.queryPedometerDataFromDate(date(from), toDate = date(atMs)) { data, _ ->
            scope.launch {
                duringPauses += data?.counters() ?: PadCounters.Zero
                held = null
                report()
            }
        }
    }

    override fun stop() {
        stopReporting()
        pedometer.stopPedometerUpdates()
    }

    private fun begin() {
        pedometer.startPedometerUpdatesFromDate(date(startMs)) { data, _ ->
            if (data != null) scope.launch { sinceStart = data.counters() }
        }
        startReporting()
    }

    private fun CMPedometerData.counters() = PadCounters(0, distance?.doubleValue?.roundToInt() ?: 0, numberOfSteps.intValue)

    companion object {
        /** Starts counting from [startMs]; the first call asks for motion access. */
        suspend fun begin(id: String, startMs: Long, emit: (HealthEvent) -> Unit, scope: CoroutineScope): TrackerStart {
            if (!CMPedometer.isStepCountingAvailable()) return TrackerStart.Rejected(RejectReason.Unsupported)
            if (deniedAccess()) return TrackerStart.Rejected(RejectReason.MotionAccessDenied)
            // A query shows the permission prompt and waits for the answer.
            val now = nowMs()
            suspendCancellableCoroutine { cont ->
                CMPedometer().queryPedometerDataFromDate(date(now - 1_000), toDate = date(now)) { _, _ -> cont.resume(Unit) }
            }
            if (deniedAccess()) return TrackerStart.Rejected(RejectReason.MotionAccessDenied)
            return TrackerStart.Started(PedometerTracker(id, startMs, emit, scope).also { it.begin() })
        }

        private fun deniedAccess() = CMPedometer.authorizationStatus().let { it == CMAuthorizationStatusDenied || it == CMAuthorizationStatusRestricted }
    }
}

/** Counts like a brisk walk, for the simulator, which has no motion sensors. */
internal class SimulatedTracker(id: String, emit: (HealthEvent) -> Unit, scope: CoroutineScope) : CountingTracker(id, emit, scope) {
    private val startMs = nowMs()
    private var pausedMs = 0L
    private var pausedAt: Long? = null

    init {
        startReporting()
    }

    override fun counters(): PadCounters {
        val activeMs = (pausedAt ?: nowMs()) - startMs - pausedMs
        val seconds = (activeMs / 1000).toInt()
        return PadCounters(seconds, seconds * 13 / 10, seconds * 9 / 5)
    }

    override fun pause(atMs: Long) {
        pausedAt = atMs
    }

    override fun resume(atMs: Long) {
        pausedAt?.let { pausedMs += atMs - it }
        pausedAt = null
    }

    override fun stop() = stopReporting()
}

/** The HealthKit quantity types the trackers read. */
internal class HealthTypes(val distance: HKQuantityType, val steps: HKQuantityType, val energy: HKQuantityType)
