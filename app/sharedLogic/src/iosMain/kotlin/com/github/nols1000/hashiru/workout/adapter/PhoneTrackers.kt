package com.github.nols1000.hashiru.workout.adapter

import com.github.nols1000.hashiru.workout.domain.HealthEvent
import com.github.nols1000.hashiru.workout.domain.HeartRateStats
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.RejectReason
import com.github.nols1000.hashiru.workout.domain.TrackedTotals
import com.github.nols1000.hashiru.workout.domain.WorkoutEvent
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
import platform.HealthKit.HKDevice
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKLiveWorkoutBuilder
import platform.HealthKit.HKLiveWorkoutBuilderDelegateProtocol
import platform.HealthKit.HKLiveWorkoutDataSource
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkout
import platform.HealthKit.HKWorkoutBuilder
import platform.HealthKit.HKWorkoutConfiguration
import platform.HealthKit.HKWorkoutSession
import platform.HealthKit.HKWorkoutSessionDelegateProtocol
import platform.HealthKit.HKWorkoutSessionState
import platform.HealthKit.HKWorkoutSessionStateEnded
import platform.HealthKit.HKWorkoutSessionStatePaused
import platform.HealthKit.countUnit
import platform.HealthKit.kilocalorieUnit
import platform.HealthKit.meterUnit
import platform.HealthKit.minuteUnit
import platform.HealthKit.recoverActiveWorkoutSessionWithCompletion
import platform.HealthKit.unitDividedByUnit
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Tracks one workout on the phone. All calls come on the main thread. */
internal interface PhoneTracker {
    val id: String

    fun pause(atMs: Long)

    /** [activeSeconds] is how long the workout was active up to [atMs], for a tracker that lost count in a restart. */
    fun resume(atMs: Long, activeSeconds: Int)

    /** Takes the pad's counts so far into the workout, where this tracker holds its samples. */
    fun count(counted: WorkoutEvent.Counted) {}

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
 * never go down: the recorder would take that for a reset. A tracker that only times a pad workout reports its
 * time alone, through [report].
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

    /** The counters last reported. */
    protected val reported: PadCounters get() = last

    protected fun report() {
        val now = counters() ?: return
        last = PadCounters(maxOf(last.seconds, now.seconds), maxOf(last.meters, now.meters), maxOf(last.steps, now.steps))
        emit(report(last))
    }

    protected open fun report(counters: PadCounters): HealthEvent =
        HealthEvent.Progress(id, TrackedTotals(counters.seconds, counters.meters, counters.steps))

    private companion object {
        const val TICK_MS = 1_000L
    }
}

/**
 * A workout running as an iPhone [HKWorkoutSession] (iOS 26 and later), whose [HKLiveWorkoutBuilder] also saves
 * it. The session times the workout and estimates its energy; with [countsMotion] it also collects steps and
 * distance from the phone's motion sensors, otherwise it is given the pad's counts as samples. It reports all of
 * it from the builder. Outdoors, where the builder has no distance, [route] measures it. If the system ends the
 * session early, a pad walk is timed on by the phone's clock.
 */
@OptIn(ExperimentalForeignApi::class)
internal class LiveWorkout private constructor(
    id: String,
    private val session: HKWorkoutSession,
    private val countsMotion: Boolean,
    private val types: HealthTypes,
    /** Turns the pad's counts into samples; null where the session counts motion itself. */
    private val pad: PadSamples?,
    private val emit: (HealthEvent) -> Unit,
    private val scope: CoroutineScope,
    private val route: RouteTracker?,
) : CountingTracker(id, emit, scope) {
    private val builder: HKLiveWorkoutBuilder = session.associatedWorkoutBuilder()
    private val delegate = Delegate()
    private var finishing = false
    private var paused = session.state == HKWorkoutSessionStatePaused

    /** Times a pad workout once its session ended early. */
    private var clock: ActiveClock? = null

    /** False once the system ended the session; its builder can no longer save the workout. */
    var usable = true
        private set

    override fun counters(): PadCounters {
        clock?.let { return PadCounters(it.seconds(nowMs()), 0, 0) }
        val meters = builder.sum(types.distance, HKUnit.meterUnit()).roundToInt()
        return PadCounters(builder.elapsedTime.toInt(), meters.takeIf { it > 0 } ?: route?.meters ?: 0, builder.sum(types.steps, HKUnit.countUnit()).roundToInt())
    }

    override fun report(counters: PadCounters): HealthEvent {
        // Once the session ended early, only the time goes on; the recorder estimates the rest.
        if (clock != null) return HealthEvent.Progress(id, TrackedTotals(counters.seconds))
        val active = builder.sum(types.energy, HKUnit.kilocalorieUnit())
        val basal = builder.sum(types.basal, HKUnit.kilocalorieUnit())
        return HealthEvent.Progress(
            id,
            TrackedTotals(counters.seconds, counters.meters, counters.steps, active, active + basal, builder.heartRate(types.heartRate)),
        )
    }

    override fun count(counted: WorkoutEvent.Counted) {
        val samples = pad?.samples(counted).orEmpty()
        if (samples.isEmpty() || !usable) return
        builder.addSamples(samples) { _, error -> error?.let { println("LiveWorkout: could not add the pad's counts: ${it.localizedDescription}") } }
    }

    override fun pause(atMs: Long) {
        paused = true
        clock?.pause(atMs)
        session.pause()
    }

    override fun resume(atMs: Long, activeSeconds: Int) {
        paused = false
        clock?.resume(atMs)
        session.resume()
    }

    override fun stop() {
        finishing = true
        stopReporting()
        session.end()
        builder.discardWorkout()
    }

    /**
     * Ends the session at [endMs] and saves the workout with [metadata]; throws if it cannot. The builder already
     * holds the samples and pauses.
     */
    suspend fun finish(endMs: Long, metadata: Map<Any?, *>): HKWorkout? {
        finishing = true
        stopReporting()
        check(usable) { "The workout session ended early" }
        val end = date(endMs)
        session.stopActivityWithDate(end)
        try {
            await { builder.endCollectionWithEndDate(end) { _, error -> it.complete(error) } }
            await { builder.addMetadata(metadata) { _, error -> it.complete(error) } }
            return builder.finish()
        } finally {
            session.end()
        }
    }

    private fun attach() {
        session.setDelegate(delegate)
        builder.setDelegate(delegate)
        startReporting()
    }

    private fun onCollected() {
        if (clock == null) report()
    }

    private fun onEnded() {
        if (finishing || !usable) return
        usable = false
        emit(HealthEvent.TrackingLost(id))
        if (countsMotion) {
            stopReporting()
        } else {
            // The pad still counts; only the time needs a new source.
            clock = ActiveClock(reported.seconds * 1000L, if (paused) null else nowMs())
        }
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
            device: HKDevice,
            emit: (HealthEvent) -> Unit,
            scope: CoroutineScope,
            route: RouteTracker? = null,
        ): TrackerStart {
            val session = try {
                HKWorkoutSession(store, configuration, null)
            } catch (e: Exception) {
                println("LiveWorkout: could not create a session: ${e.message}")
                return TrackerStart.Failed
            }
            val dataSource = HKLiveWorkoutDataSource(store, configuration)
            dataSource.enableCollectionForType(types.energy, predicate = null)
            dataSource.enableCollectionForType(types.basal, predicate = null)
            if (!countsMotion) {
                // The pad's counts come in as samples instead.
                dataSource.disableCollectionForType(types.distance)
                dataSource.disableCollectionForType(types.steps)
            }
            val workout = LiveWorkout(id, session, countsMotion, types, if (countsMotion) null else PadSamples(id, startMs, types, device), emit, scope, route)
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
            device: HKDevice,
            emit: (HealthEvent) -> Unit,
            scope: CoroutineScope,
            route: RouteTracker? = null,
        ): LiveWorkout? {
            val session = suspendCancellableCoroutine<HKWorkoutSession?> { cont ->
                store.recoverActiveWorkoutSessionWithCompletion { session, _ -> cont.resume(session) }
            } ?: return null
            val builder = session.associatedWorkoutBuilder()
            // The builder kept the counts it was given; only newer ones are added.
            val pad = if (countsMotion) null else PadSamples(id, builder.startDate?.epochMs() ?: nowMs(), types, device).also { it.seed(builder, types) }
            return LiveWorkout(id, session, countsMotion, types, pad, emit, scope, route).also { it.attach() }
        }
    }
}

/**
 * Counts steps and distance with [CMPedometer], for iPhones without workout sessions (before iOS 26) or where
 * one could not start. The pedometer counts from the start on its own, also while the app is suspended; what it
 * counted during pauses is left out. Outdoors, [route] measures the distance once it has a fix: GPS is closer
 * than stride length, the more so when running.
 */
@OptIn(ExperimentalForeignApi::class)
internal class PedometerTracker private constructor(
    id: String,
    private val startMs: Long,
    emit: (HealthEvent) -> Unit,
    private val scope: CoroutineScope,
    private val route: RouteTracker?,
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
        val meters = route?.meters ?: (sinceStart.meters - duringPauses.meters)
        return PadCounters((activeMs / 1000).toInt().coerceAtLeast(0), meters, sinceStart.steps - duringPauses.steps)
    }

    override fun pause(atMs: Long) {
        held = counters()
        pausedAt = atMs
    }

    override fun resume(atMs: Long, activeSeconds: Int) {
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
        suspend fun begin(id: String, startMs: Long, emit: (HealthEvent) -> Unit, scope: CoroutineScope, route: RouteTracker? = null): TrackerStart {
            if (!CMPedometer.isStepCountingAvailable()) return TrackerStart.Rejected(RejectReason.Unsupported)
            if (deniedAccess()) return TrackerStart.Rejected(RejectReason.MotionAccessDenied)
            // A query shows the permission prompt and waits for the answer.
            val now = nowMs()
            suspendCancellableCoroutine { cont ->
                CMPedometer().queryPedometerDataFromDate(date(now - 1_000), toDate = date(now)) { _, _ -> cont.resume(Unit) }
            }
            if (deniedAccess()) return TrackerStart.Rejected(RejectReason.MotionAccessDenied)
            return TrackerStart.Started(PedometerTracker(id, startMs, emit, scope, route).also { it.begin() })
        }

        private fun deniedAccess() = CMPedometer.authorizationStatus().let { it == CMAuthorizationStatusDenied || it == CMAuthorizationStatusRestricted }
    }
}

/** Active time on the phone's clock: [baseMs], plus the time since [sinceMs] unless paused (null). */
internal class ActiveClock(private var baseMs: Long, private var sinceMs: Long?) {
    fun seconds(nowMs: Long): Int = ((baseMs + (sinceMs?.let { nowMs - it } ?: 0L).coerceAtLeast(0)) / 1000).toInt()

    fun pause(atMs: Long) {
        sinceMs?.let { baseMs += (atMs - it).coerceAtLeast(0) }
        sinceMs = null
    }

    fun resume(atMs: Long) {
        if (sinceMs == null) sinceMs = atMs
    }
}

/**
 * Times a pad workout on the phone's clock, where no workout session can: before iOS 26, in the simulator, or after
 * a restart without a session to recover. It starts at [startMs]; without one, after a restart, it waits for the
 * walk to resume and goes on from the time the walk had then.
 */
internal class ClockTracker(id: String, startMs: Long?, emit: (HealthEvent) -> Unit, scope: CoroutineScope) : CountingTracker(id, emit, scope) {
    private var clock = startMs?.let { ActiveClock(0, it) }

    init {
        startReporting()
    }

    override fun counters() = clock?.let { PadCounters(it.seconds(nowMs()), 0, 0) }

    override fun report(counters: PadCounters) = HealthEvent.Progress(id, TrackedTotals(counters.seconds))

    override fun pause(atMs: Long) {
        clock?.pause(atMs)
    }

    override fun resume(atMs: Long, activeSeconds: Int) {
        val clock = clock
        if (clock != null) clock.resume(atMs) else this.clock = ActiveClock(activeSeconds * 1000L, atMs)
    }

    override fun stop() = stopReporting()
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

    override fun resume(atMs: Long, activeSeconds: Int) {
        pausedAt?.let { pausedMs += atMs - it }
        pausedAt = null
    }

    override fun stop() = stopReporting()
}

/** The HealthKit quantity types the trackers read. */
internal class HealthTypes(
    val distance: HKQuantityType,
    val steps: HKQuantityType,
    val energy: HKQuantityType,
    val basal: HKQuantityType,
    val heartRate: HKQuantityType,
)

/** The sum of [type]'s samples in the workout so far, in [unit]. */
internal fun HKWorkoutBuilder.sum(type: HKQuantityType, unit: HKUnit): Double =
    statisticsForType(type)?.sumQuantity()?.doubleValueForUnit(unit) ?: 0.0

/** The workout's heart rate so far, or null before the first reading. */
internal fun HKWorkoutBuilder.heartRate(type: HKQuantityType): HeartRateStats? {
    val statistics = statisticsForType(type) ?: return null
    val bpm = HKUnit.countUnit().unitDividedByUnit(HKUnit.minuteUnit())
    val avg = statistics.averageQuantity()?.doubleValueForUnit(bpm) ?: return null
    val max = statistics.maximumQuantity()?.doubleValueForUnit(bpm) ?: return null
    return HeartRateStats(statistics.mostRecentQuantity()?.doubleValueForUnit(bpm)?.roundToInt(), avg.roundToInt(), max.roundToInt())
}
