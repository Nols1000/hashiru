package com.github.nols1000.hashiru.share.adapter

import com.github.nols1000.hashiru.share.codec.Base64Url
import com.github.nols1000.hashiru.share.port.ShareStreamPort
import com.github.nols1000.hashiru.share.port.StreamEvent
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.js.Promise

private external interface Response {
    val status: Int
    fun text(): Promise<String>
}

private external fun fetch(url: String, init: dynamic = definedExternally): Promise<Response>

private external class EventSource(url: String) {
    var onerror: ((dynamic) -> Unit)?
    fun addEventListener(type: String, listener: (dynamic) -> Unit)
    fun close()
}

/**
 * The relay through `fetch` and server-sent events, on the viewer's own origin under [apiBase], where the static
 * host's proxy passes `/api` on to the relay. EventSource would reconnect on its own with the spent challenge, so
 * an error ends the flow instead and the viewer starts over.
 */
class BrowserShareStream(private val apiBase: String = "") : ShareStreamPort {
    override suspend fun challenge(shareId: String): String? {
        val init: dynamic = js("({ cache: 'no-store' })")
        val response = fetch("$apiBase/api/shares/$shareId/challenge", init).await()
        if (response.status == 404) return null
        if (response.status != 200) throw IllegalStateException("challenge answered ${response.status}")
        return response.text().await()
    }

    override fun stream(shareId: String, nonce: String, proof: ByteArray): Flow<StreamEvent> = callbackFlow {
        val url = "$apiBase/api/shares/$shareId/stream?nonce=${encodeURIComponent(nonce)}&proof=${Base64Url.encode(proof)}"
        val source = EventSource(url)
        fun frame(snapshot: Boolean): (dynamic) -> Unit = { event ->
            val bytes = Base64Url.decode(event.data as String)
            val seq = (event.lastEventId as String).toLongOrNull()
            if (bytes != null && seq != null) trySend(StreamEvent.Frame(seq, snapshot, bytes))
        }
        source.addEventListener("s", frame(snapshot = true))
        source.addEventListener("u", frame(snapshot = false))
        source.addEventListener("e") { trySend(StreamEvent.Ended) }
        source.addEventListener("denied") {
            trySend(StreamEvent.Denied)
            close()
        }
        source.addEventListener("full") {
            trySend(StreamEvent.Full)
            close()
        }
        source.onerror = { close() }
        awaitClose { source.close() }
    }
}

private external fun encodeURIComponent(value: String): String
