package com.github.nols1000.hashiru.treadmill.engine

import com.github.nols1000.hashiru.treadmill.codec.WalkingPadPacketCodec
import com.github.nols1000.hashiru.treadmill.domain.BeltState
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.domain.TelemetrySnapshot
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.treadmill.domain.TreadmillEvent
import com.github.nols1000.hashiru.treadmill.port.TreadmillTransportPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Polls the treadmill for status while connected, decodes status packets into [telemetry] and [events],
 * and dispatches commands.
 *
 * The pad only reports status in reply to a ping, so the next ping is sent [pollInterval] after each
 * reply. On iOS each reply also wakes a suspended app, which keeps the chain going in the background.
 * If no reply arrives within [responseTimeout], the engine pings again anyway.
 *
 * Writes are serialized and spaced at least [minFrameGap] apart so the pad's receiver keeps up. Speed
 * changes are conflated: while a write is in flight, only the most recent requested speed is sent next.
 */
class WalkingPadEngine(
    private val transport: TreadmillTransportPort,
    private val scope: CoroutineScope,
    private val pollInterval: Duration = 750.milliseconds,
    private val responseTimeout: Duration = 1.seconds,
    private val minFrameGap: Duration = 250.milliseconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val _telemetry = MutableStateFlow(TelemetrySnapshot())
    val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    private val _events = MutableSharedFlow<TreadmillEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TreadmillEvent> = _events.asSharedFlow()

    private val sendMutex = Mutex()
    private val pendingSpeedTenths = MutableStateFlow<Int?>(null)
    private val statusCount = MutableStateFlow(0L)
    private var lastFrame: TimeMark? = null
    private var lastBeltState: BeltState? = null
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch {
                transport.incomingBytes.collect { bytes ->
                    val status = WalkingPadPacketCodec.decodeStatus(bytes) ?: return@collect
                    _telemetry.value = TelemetrySnapshot(
                        speedKmh = status.speedKmh,
                        isRunning = status.beltState == BeltState.Running,
                        elapsedSeconds = status.timeSeconds,
                        distanceMeters = status.distanceMeters,
                        steps = status.steps,
                    )
                    statusCount.value++
                    if (status.beltState != lastBeltState) {
                        lastBeltState = status.beltState
                        _events.emit(TreadmillEvent.BeltStateChanged(status.beltState))
                    }
                }
            }
            launch {
                transport.restoredSessions.collect { _events.emit(TreadmillEvent.SessionRestoredInBackground) }
            }
            launch {
                transport.connectionState.collectLatest { state ->
                    if (state != ConnectionState.Connected) {
                        lastBeltState = null
                        // Forget the last report, so the first one after reconnecting is always a fresh value.
                        _telemetry.value = TelemetrySnapshot()
                        return@collectLatest
                    }
                    while (true) {
                        val repliesBefore = statusCount.value
                        try {
                            dispatch(TreadmillCommand.RequestStatusPing)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // A dropped ping is recovered by the next one; a real disconnect flips connectionState.
                        }
                        withTimeoutOrNull(responseTimeout) { statusCount.first { it > repliesBefore } }
                        delay(pollInterval)
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun dispatch(command: TreadmillCommand) {
        if (command is TreadmillCommand.SetSpeed) pendingSpeedTenths.value = command.tenthsKmh
        sendMutex.withLock {
            awaitFrameGap()
            val frames = when (command) {
                is TreadmillCommand.SetSpeed -> {
                    // Read after the gap so every request made meanwhile collapses into this one write.
                    val tenths = pendingSpeedTenths.value ?: return
                    pendingSpeedTenths.value = null
                    WalkingPadPacketCodec.encode(TreadmillCommand.SetSpeed(tenths))
                }
                TreadmillCommand.StopBelt -> {
                    pendingSpeedTenths.value = null
                    WalkingPadPacketCodec.encode(command)
                }
                else -> WalkingPadPacketCodec.encode(command)
            }
            frames.forEachIndexed { i, frame ->
                if (i > 0) awaitFrameGap()
                try {
                    transport.send(frame)
                } finally {
                    lastFrame = timeSource.markNow()
                }
            }
        }
    }

    private suspend fun awaitFrameGap() {
        lastFrame?.let { delay(minFrameGap - it.elapsedNow()) }
    }
}
