package com.github.nols1000.hashiru.companion.adapter

import android.content.Context
import android.util.Log
import com.github.nols1000.hashiru.companion.codec.WatchMessage
import com.github.nols1000.hashiru.companion.codec.WatchMessageCodec
import com.github.nols1000.hashiru.companion.domain.CompanionState
import com.github.nols1000.hashiru.companion.domain.IncomingCommand
import com.github.nols1000.hashiru.companion.domain.WristTelemetry
import com.github.nols1000.hashiru.companion.port.WatchCompanionPort
import com.github.nols1000.hashiru.course.domain.Course
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Talks to the Wear OS app over the Data Layer. Commands and heart rate arrive as messages, which the app's
 * `WearableListenerService` hands to [onMessage] (it also wakes the app for them); telemetry goes out as
 * messages to reachable watches, and the walk's phase as an urgent data item.
 *
 * Phones without the Wear OS app or Play services make every call fail; the adapter then reports
 * [CompanionState.NotPaired] and drops what it would send.
 */
class WearDataLayerAdapter(context: Context) : WatchCompanionPort {
    private val appContext = context.applicationContext
    private val messages = Wearable.getMessageClient(appContext)
    private val data = Wearable.getDataClient(appContext)
    private val capabilities = Wearable.getCapabilityClient(appContext)
    private val nodes = Wearable.getNodeClient(appContext)
    private val scope = MainScope()

    private val _state = MutableStateFlow(CompanionState.NotPaired)
    override val state: StateFlow<CompanionState> = _state.asStateFlow()

    private val _heartRate = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 16)
    override val heartRate: Flow<HeartRateSample> = _heartRate

    private val _commands = MutableSharedFlow<IncomingCommand>(extraBufferCapacity = 16)
    override val commands: Flow<IncomingCommand> = _commands

    @Volatile private var reachable: List<Node> = emptyList()

    init {
        try {
            capabilities.addListener({ refresh() }, WearDataLayer.CAPABILITY_WATCH)
        } catch (e: Exception) {
            Log.d(TAG, "Wearable API unavailable", e)
        }
        refresh()
    }

    /** Handles a message from the watch; called on a binder thread by the listener service. */
    fun onMessage(sourceNodeId: String, path: String, bytes: ByteArray) {
        when (val message = WatchMessageCodec.decode(bytes)) {
            is WatchMessage.HeartRate -> if (path == WearDataLayer.PATH_HEART_RATE) _heartRate.tryEmit(message.sample)
            is WatchMessage.Command -> if (path == WearDataLayer.PATH_COMMAND) {
                _commands.tryEmit(IncomingCommand(message.command) { ok -> reply(sourceNodeId, ok) })
            }
            else -> Log.w(TAG, "Ignoring message on $path")
        }
        // A message proves the watch is reachable, even before the capability callback says so.
        if (_state.value != CompanionState.Reachable) refresh()
    }

    override suspend fun pushTelemetry(telemetry: WristTelemetry) {
        val bytes = WatchMessageCodec.encode(WatchMessage.Telemetry(telemetry))
        for (node in reachable) messages.sendMessage(node.id, WearDataLayer.PATH_TELEMETRY, bytes).await()
    }

    override suspend fun pushState(telemetry: WristTelemetry) {
        if (_state.value == CompanionState.NotPaired) return
        val request = PutDataMapRequest.create(WearDataLayer.PATH_STATE).apply {
            dataMap.putByteArray(WearDataLayer.KEY_STATE, WatchMessageCodec.encode(WatchMessage.Telemetry(telemetry)))
        }.asPutDataRequest().setUrgent()
        data.putDataItem(request).await()
    }

    /** The Wear OS app does not navigate courses yet. */
    override suspend fun pushCourse(course: Course?) = Unit

    /** The watch starts its exercise when the phase in [pushState] turns active, so there is nothing more to send. */
    override suspend fun requestCompanionStart() = Unit

    private fun reply(nodeId: String, ok: Boolean) {
        scope.launch {
            try {
                messages.sendMessage(nodeId, WearDataLayer.PATH_ACK, WatchMessageCodec.encode(WatchMessage.Ack(ok))).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not answer the watch", e)
            }
        }
    }

    /** Reachable watches with the app, else known ones with it, else any watch at all, else none. */
    private fun refresh() {
        scope.launch {
            _state.value = try {
                val near = capabilities.getCapability(WearDataLayer.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes
                reachable = near.toList()
                when {
                    near.isNotEmpty() -> CompanionState.Reachable
                    capabilities.getCapability(WearDataLayer.CAPABILITY_WATCH, CapabilityClient.FILTER_ALL).await().nodes.isNotEmpty() ->
                        CompanionState.Unreachable
                    nodes.connectedNodes.await().isNotEmpty() -> CompanionState.AppNotInstalled
                    else -> CompanionState.NotPaired
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reachable = emptyList()
                CompanionState.NotPaired
            }
        }
    }

    private companion object {
        const val TAG = "WearDataLayer"
    }
}
