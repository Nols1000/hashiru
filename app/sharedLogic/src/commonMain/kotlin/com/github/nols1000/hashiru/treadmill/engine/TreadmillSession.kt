package com.github.nols1000.hashiru.treadmill.engine

import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.domain.TelemetrySnapshot
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import com.github.nols1000.hashiru.treadmill.domain.TreadmillEvent
import com.github.nols1000.hashiru.treadmill.port.DeviceAssociation
import com.github.nols1000.hashiru.treadmill.port.DeviceAssociationPort
import com.github.nols1000.hashiru.treadmill.port.TreadmillTransportPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * App-scoped owner of the treadmill connection. It outlives any screen, so the session keeps running
 * while the UI is gone and a newly mounted UI picks up the current [telemetry] straight away.
 *
 * Once a device is associated, the session connects to it whenever the OS reports it nearby and lets
 * go of the link when the OS reports it gone.
 */
class TreadmillSession internal constructor(
    private val transport: TreadmillTransportPort,
    private val associations: DeviceAssociationPort,
    private val scope: CoroutineScope,
    timeSource: TimeSource,
) {
    /**
     * Creates a session that lives on the main thread for the rest of the process; keep a single instance.
     * The immediate dispatcher lets [start], called on the main thread, subscribe to presence changes before
     * it returns, so a presence report that follows straight after (cold start by the OS) is not lost.
     */
    constructor(transport: TreadmillTransportPort, associations: DeviceAssociationPort) :
        this(transport, associations, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), TimeSource.Monotonic)

    private val engine = WalkingPadEngine(transport, scope, timeSource = timeSource)

    val connectionState: StateFlow<ConnectionState> get() = transport.connectionState
    val telemetry: StateFlow<TelemetrySnapshot> get() = engine.telemetry
    val association: StateFlow<DeviceAssociation?> get() = associations.association

    val events: Flow<TreadmillEvent> = merge(
        engine.events,
        associations.presenceChanges.map { nearby ->
            if (nearby) TreadmillEvent.DeviceAppearedNearby else TreadmillEvent.DeviceDisappeared
        },
    )

    private var started = false

    fun start() {
        if (started) return
        started = true
        engine.start()
        scope.launch {
            associations.presenceChanges.collect { nearby ->
                when {
                    !nearby -> transport.disconnect()
                    // A pending connect can wait a long time; launch it so later presence changes still get through.
                    transport.connectionState.value == ConnectionState.Disconnected -> launch {
                        try {
                            connect()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // The next presence change retries.
                        }
                    }
                }
            }
        }
    }

    /** Whether the OS offers system pairing; without it, [connect] scans for the first matching device. */
    val canPair: Boolean get() = associations.isAvailable

    /**
     * Connects to the associated device, or to the first matching device found when none is associated.
     * The attempt runs in the session's scope, so cancelling the caller (a screen going away) leaves it running.
     */
    suspend fun connect() = connectTo(associations.association.value?.id)

    fun disconnect() = transport.disconnect()

    /** Lets the user pick a device in the system picker, then connects to it. */
    suspend fun pair() {
        val association = associations.associate()
        connectTo(association.id)
    }

    suspend fun forget() {
        transport.disconnect()
        associations.disassociate()
    }

    suspend fun dispatch(command: TreadmillCommand) = engine.dispatch(command)

    private suspend fun connectTo(address: String?) = scope.async { transport.connect(address) }.await()
}
