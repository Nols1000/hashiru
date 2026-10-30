package com.github.nols1000.hashiru.wear

import android.content.Context
import android.util.Log
import com.github.nols1000.hashiru.companion.adapter.WearDataLayer
import com.github.nols1000.hashiru.companion.adapter.await
import com.github.nols1000.hashiru.companion.codec.WatchMessage
import com.github.nols1000.hashiru.companion.codec.WatchMessageCodec
import com.github.nols1000.hashiru.companion.domain.WristCommand
import com.github.nols1000.hashiru.companion.domain.WristTelemetry
import com.github.nols1000.hashiru.workout.domain.HeartRateSample
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** The watch's side of the Data Layer link to the phone app. */
class PhoneLink(context: Context) {
    private val messages = Wearable.getMessageClient(context)
    private val capabilities = Wearable.getCapabilityClient(context)
    private val data = Wearable.getDataClient(context)

    @Volatile private var pendingAck: CompletableDeferred<Boolean>? = null

    /** The phone running the app, if it is in reach. */
    suspend fun phoneNodeId(): String? = quietly {
        capabilities.getCapability(WearDataLayer.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes.firstOrNull()?.id
    }

    /** Sends [command] and waits up to [ACK_TIMEOUT_MS] for the phone to say it was carried out. */
    suspend fun send(command: WristCommand): Boolean {
        val node = phoneNodeId() ?: return false
        val ack = CompletableDeferred<Boolean>()
        // Commands come from one screen, one at a time; a newer one supersedes the wait for an older answer.
        pendingAck?.complete(false)
        pendingAck = ack
        quietly { messages.sendMessage(node, WearDataLayer.PATH_COMMAND, WatchMessageCodec.encode(WatchMessage.Command(command))).await() }
            ?: return false
        return withTimeoutOrNull(ACK_TIMEOUT_MS) { ack.await() } ?: false
    }

    fun onAck(ok: Boolean) {
        pendingAck?.complete(ok)
    }

    suspend fun sendHeartRate(sample: HeartRateSample) {
        val node = phoneNodeId() ?: return
        quietly { messages.sendMessage(node, WearDataLayer.PATH_HEART_RATE, WatchMessageCodec.encode(WatchMessage.HeartRate(sample))).await() }
    }

    /** The phase the phone last stored, for a watch app that starts after it was sent. */
    suspend fun lastState(): WristTelemetry? = quietly {
        val items = data.dataItems.await()
        try {
            items.firstOrNull { it.uri.path == WearDataLayer.PATH_STATE }
                ?.let { DataMapItem.fromDataItem(it).dataMap.getByteArray(WearDataLayer.KEY_STATE) }
                ?.let { (WatchMessageCodec.decode(it) as? WatchMessage.Telemetry)?.telemetry }
        } finally {
            items.release()
        }
    }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Data Layer call failed", e)
        null
    }

    companion object {
        private const val TAG = "PhoneLink"
        const val ACK_TIMEOUT_MS = 1_500L
    }
}
