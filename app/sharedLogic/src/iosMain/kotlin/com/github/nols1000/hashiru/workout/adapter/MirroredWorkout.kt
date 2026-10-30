package com.github.nols1000.hashiru.workout.adapter

import com.github.nols1000.hashiru.toByteArray
import com.github.nols1000.hashiru.toNSData
import com.github.nols1000.hashiru.workout.codec.SessionMessage
import com.github.nols1000.hashiru.workout.codec.SessionMessageCodec
import com.github.nols1000.hashiru.workout.domain.HealthEvent
import com.github.nols1000.hashiru.workout.domain.TrackedTotals
import com.github.nols1000.hashiru.workout.domain.WorkoutEvent
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import com.github.nols1000.hashiru.workout.domain.WorkoutSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.HealthKit.HKWorkoutSession
import platform.HealthKit.HKWorkoutSessionDelegateProtocol
import platform.HealthKit.HKWorkoutSessionState
import platform.darwin.NSObject
import kotlin.time.Duration.Companion.seconds

/**
 * A workout whose primary session runs on the Apple Watch and is mirrored to this phone. The watch times it,
 * estimates its energy, measures heart rate, holds the pad's counts as samples and saves it; it sends its totals
 * back once a second. Pauses go through the mirrored session; everything else travels as [SessionMessage]s.
 *
 * Messages may be lost, so the counts are cumulative and the end is repeated until the watch answers. When the
 * watch goes quiet while walking, it waits for the mirror to come back ([reattach]); after [LOST_MS] the phone's
 * clock times the walk on and the recorder estimates the rest.
 */
@OptIn(ExperimentalForeignApi::class)
internal class MirroredWorkout(
    override val id: String,
    private var session: HKWorkoutSession,
    private val emit: (HealthEvent) -> Unit,
    private val scope: CoroutineScope,
) : PhoneTracker {
    private val delegate = Delegate()
    private var paused = false
    private var ended = false
    private var lastSeconds = 0
    private var lastHeardMs = nowMs()
    private var lastCounted: SessionMessage.Counted? = null
    private var result: CompletableDeferred<SessionMessage.Result>? = null

    /** Times the walk once the watch was lost. */
    private var clock: ActiveClock? = null
    private val watchdog: Job

    init {
        session.delegate = delegate
        watchdog = scope.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    /** Has the watch start its session at [startMs]. */
    fun start(startMs: Long, kind: WorkoutKind, source: WorkoutSource) {
        send(SessionMessage.Start(id, startMs, kind, source))
    }

    /** Takes over a new mirror of the same workout, as after the watch reconnected. */
    fun reattach(mirror: HKWorkoutSession) {
        session = mirror
        session.delegate = delegate
        lastHeardMs = nowMs()
        lastCounted?.let(::send)
    }

    override fun pause(atMs: Long) {
        paused = true
        clock?.pause(atMs)
        session.pause()
    }

    override fun resume(atMs: Long, activeSeconds: Int) {
        paused = false
        lastHeardMs = nowMs()
        clock?.resume(atMs)
        session.resume()
    }

    override fun count(counted: WorkoutEvent.Counted) {
        val message = SessionMessage.Counted(counted.atMs, counted.meters, counted.steps)
        lastCounted = message
        send(message)
    }

    override fun stop() {
        close()
        send(SessionMessage.Discard)
    }

    /** Has the watch end the walk at [endMs] and save it; null when it did not answer in time. */
    suspend fun finish(endMs: Long, syncVersion: Long): SessionMessage.Result? {
        val answer = CompletableDeferred<SessionMessage.Result>()
        result = answer
        return try {
            withTimeoutOrNull(FINISH_TIMEOUT) {
                // Repeated until answered, as a message may be lost; the watch saves only once.
                val resend = launch {
                    while (true) {
                        send(SessionMessage.End(endMs, id, syncVersion))
                        delay(RESEND_MS)
                    }
                }
                answer.await().also { resend.cancel() }
            }
        } finally {
            close()
        }
    }

    private fun close() {
        ended = true
        watchdog.cancel()
    }

    private fun tick() {
        if (ended) return
        clock?.let {
            emit(HealthEvent.Progress(id, TrackedTotals(it.seconds(nowMs()))))
            return
        }
        // A paused session collects nothing, so the watch is quiet then.
        if (paused || nowMs() - lastHeardMs < LOST_MS) return
        println("MirroredWorkout: the watch went quiet; the phone times the walk on")
        emit(HealthEvent.TrackingLost(id))
        clock = ActiveClock(lastSeconds * 1000L, nowMs())
    }

    private fun receive(message: SessionMessage) {
        when (message) {
            is SessionMessage.Totals -> {
                if (ended) return
                lastHeardMs = nowMs()
                lastSeconds = message.totals.seconds
                // Back from being lost: the watch's totals take over again.
                clock = null
                emit(HealthEvent.Progress(id, message.totals))
            }
            is SessionMessage.Result -> result?.complete(message)
            else -> Unit
        }
    }

    private fun send(message: SessionMessage) = send(session, message)

    /** HealthKit calls these on its own queues; everything is handled on the main thread. */
    private inner class Delegate : NSObject(), HKWorkoutSessionDelegateProtocol {
        override fun workoutSession(
            workoutSession: HKWorkoutSession,
            didChangeToState: HKWorkoutSessionState,
            fromState: HKWorkoutSessionState,
            date: NSDate,
        ) = Unit

        @ObjCSignatureOverride
        override fun workoutSession(workoutSession: HKWorkoutSession, didFailWithError: NSError) {
            println("MirroredWorkout: session failed: ${didFailWithError.localizedDescription}")
        }

        override fun workoutSession(workoutSession: HKWorkoutSession, didReceiveDataFromRemoteWorkoutSession: List<*>) {
            val messages = didReceiveDataFromRemoteWorkoutSession.filterIsInstance<NSData>().mapNotNull { SessionMessageCodec.decode(it.toByteArray()) }
            scope.launch { messages.forEach(::receive) }
        }
    }

    companion object {
        private const val TICK_MS = 1_000L

        /** How long the watch may stay quiet while walking before the phone times the walk on. */
        private const val LOST_MS = 20_000L
        private const val RESEND_MS = 5_000L
        private val FINISH_TIMEOUT = 30.seconds

        /** Sends [message] without waiting: the callback may never come. */
        fun send(session: HKWorkoutSession, message: SessionMessage) {
            session.sendDataToRemoteWorkoutSession(SessionMessageCodec.encode(message).toNSData()) { _, error ->
                error?.let { println("MirroredWorkout: could not send ${message::class.simpleName}: ${it.localizedDescription}") }
            }
        }
    }
}
