package com.github.nols1000.hashiru.treadmill.adapter

import com.github.nols1000.hashiru.treadmill.codec.WalkingPadPacketCodec
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.domain.TreadmillError
import com.github.nols1000.hashiru.treadmill.domain.TreadmillException
import com.github.nols1000.hashiru.treadmill.port.TreadmillTransportPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Simulated WalkingPad for running the app without hardware. Answers every status ping with a
 * status packet, ramps the belt towards the target speed while running, and accumulates session
 * time, distance and steps the way the real pad does. [simulateConnectionLoss] and [simulateRestore]
 * reproduce an unexpected drop and an OS-restored session, and [failNextConnect] a failed connection attempt.
 */
class MockTransportAdapter internal constructor(
    targetSpeedTenths: Int,
    private val rampStepTenths: Int,
    private val timeSource: TimeSource,
) : TreadmillTransportPort {
    constructor(targetSpeedTenths: Int = 30, rampStepTenths: Int = 5) :
        this(targetSpeedTenths, rampStepTenths, TimeSource.Monotonic)

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    override val incomingBytes: Flow<ByteArray> = _incomingBytes

    private val _restoredSessions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val restoredSessions: Flow<Unit> = _restoredSessions

    private val initialTargetTenths = targetSpeedTenths
    private var targetTenths = targetSpeedTenths
    private var running = false
    private var mode = WalkingPadPacketCodec.MODE_STANDBY
    private var speedTenths = 0

    var failNextConnect: TreadmillError? = null

    private var lastTick: TimeMark? = null
    private var elapsedSeconds = 0.0
    private var distanceMeters = 0.0

    override suspend fun connect(address: String?) {
        _connectionState.value = ConnectionState.Connecting
        delay(500)
        failNextConnect?.let {
            failNextConnect = null
            _connectionState.value = ConnectionState.Disconnected
            throw TreadmillException(it)
        }
        _connectionState.value = ConnectionState.Connected
    }

    override fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected
        running = false
        speedTenths = 0
        lastTick = null
    }

    /** Drops the link the way the radio would; the pad keeps its belt state, unlike [disconnect]. */
    fun simulateConnectionLoss() {
        _connectionState.value = ConnectionState.Disconnected
    }

    /** Reconnects as if the OS had relaunched the app and handed back the connection of the previous process. */
    fun simulateRestore() {
        _connectionState.value = ConnectionState.Connected
        _restoredSessions.tryEmit(Unit)
    }

    override suspend fun send(bytes: ByteArray) {
        check(_connectionState.value == ConnectionState.Connected) { "Not connected" }
        val setSpeed = WalkingPadPacketCodec.decodeSetSpeed(bytes)
        when {
            bytes.contentEquals(WalkingPadPacketCodec.encodePing()) -> {
                accumulate()
                speedTenths = if (running) rampTowards(targetTenths) else 0
                _incomingBytes.emit(
                    WalkingPadPacketCodec.encodeStatus(
                        beltState = if (running) 1 else 0,
                        speedTenthsKmh = speedTenths,
                        mode = mode,
                        timeSeconds = elapsedSeconds.toInt(),
                        distanceMeters = distanceMeters.toInt(),
                        steps = (distanceMeters / STRIDE_METERS).toInt(),
                    )
                )
            }
            bytes.contentEquals(WalkingPadPacketCodec.encodeSwitchMode(WalkingPadPacketCodec.MODE_MANUAL)) ->
                mode = WalkingPadPacketCodec.MODE_MANUAL
            bytes.contentEquals(WalkingPadPacketCodec.encodeStartBelt()) -> {
                running = true
                targetTenths = initialTargetTenths
                elapsedSeconds = 0.0
                distanceMeters = 0.0
                lastTick = timeSource.markNow()
            }
            setSpeed == 0 -> {
                accumulate()
                running = false
                lastTick = null
            }
            setSpeed != null -> if (running) targetTenths = setSpeed
        }
    }

    private fun rampTowards(target: Int): Int =
        if (speedTenths < target) minOf(speedTenths + rampStepTenths, target)
        else maxOf(speedTenths - rampStepTenths, target)

    private fun accumulate() {
        val tick = lastTick ?: return
        val seconds = tick.elapsedNow().toDouble(DurationUnit.SECONDS)
        elapsedSeconds += seconds
        distanceMeters += speedTenths / 10.0 / 3.6 * seconds
        lastTick = timeSource.markNow()
    }

    private companion object {
        const val STRIDE_METERS = 0.7
    }
}
