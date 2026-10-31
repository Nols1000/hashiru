package com.github.nols1000.hashiru.share

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

private val base64 = Base64.getUrlDecoder()
private val base64Out = Base64.getUrlEncoder().withoutPadding()
private val shareId = Regex("[A-Za-z0-9_-]{22}")

/**
 * The relay's API. Walkers authenticate with `Authorization: Bearer <write token>`; viewers answer a presence
 * challenge to be counted, then get the share's frames as server-sent events, `event: s` (snapshot), `u`
 * (update) or `e` (ended), with `id` the counter and the sealed frame as unpadded URL-safe base64 in `data`.
 *
 * ```
 * PUT    /api/shares/{id}                body: write token (32) ‖ presence key (32)
 * PUT    /api/shares/{id}/frames/{seq}   body: frame; ?snapshot=1 replaces the snapshot. Answers the watcher count.
 * GET    /api/shares/{id}/watchers       answers the watcher count
 * DELETE /api/shares/{id}                ends the share
 * GET    /api/shares/{id}/challenge      answers a nonce
 * GET    /api/shares/{id}/stream?nonce=…&proof=…   proof: HMAC-SHA-512(presence key, nonce)
 * ```
 *
 * A relay with no room for another share answers 503, as it does while it restarts: the phone tries again later.
 */
fun Route.shareRoutes(store: ShareStore) {
    route("/api/shares/{id}") {
        put {
            val id = call.shareId() ?: return@put
            val body = call.body(64) ?: return@put
            if (body.size != 64) return@put call.respond(HttpStatusCode.BadRequest)
            call.answer(store.open(id, body.copyOfRange(0, 32), body.copyOfRange(32, 64)))
        }
        put("/frames/{seq}") {
            val id = call.shareId() ?: return@put
            val token = call.token() ?: return@put call.respond(HttpStatusCode.Unauthorized)
            val seq = call.parameters["seq"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return@put call.respond(HttpStatusCode.BadRequest)
            val snapshot = call.request.queryParameters["snapshot"] == "1"
            val limit = if (snapshot) store.limits.maxSnapshotBytes else store.limits.maxFrameBytes
            val body = call.body(limit) ?: return@put
            if (body.isEmpty()) return@put call.respond(HttpStatusCode.BadRequest)
            when (val result = store.publish(id, token, StoredFrame(seq, snapshot, body))) {
                ShareStore.Result.Ok -> call.respondText(store.watchers(id, token).toString())
                else -> call.answer(result)
            }
        }
        get("/watchers") {
            val id = call.shareId() ?: return@get
            val token = call.token() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val count = store.watchers(id, token) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondText(count.toString())
        }
        delete {
            val id = call.shareId() ?: return@delete
            val token = call.token() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
            call.answer(store.close(id, token))
        }
        get("/challenge") {
            val id = call.shareId() ?: return@get
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(store.challenge(id) ?: return@get call.respond(HttpStatusCode.NotFound))
        }
        sse("/stream") {
            val id = call.parameters["id"]?.takeIf(shareId::matches) ?: return@sse
            val nonce = call.request.queryParameters["nonce"] ?: return@sse
            val proof = call.request.queryParameters["proof"]?.let { runCatching { base64.decode(it) }.getOrNull() } ?: return@sse
            val share = store.admit(id, nonce, proof) ?: run {
                send(ServerSentEvent(event = "denied", data = ""))
                return@sse
            }
            if (!store.join(share)) {
                send(ServerSentEvent(event = "full", data = ""))
                return@sse
            }
            // A comment now and then, which viewers ignore, keeps proxies and the load balancer from closing a quiet stream.
            heartbeat { period = 30.seconds }
            val watch = store.watch(share)
            try {
                var last = Long.MIN_VALUE
                watch.held.forEach {
                    send(it.event())
                    last = it.seq
                }
                if (watch.ended) send(ServerSentEvent(event = "e", data = ""))
                // Ends when the relay closes the channel: the viewer fell behind, or the share is gone.
                for (signal in watch.signals) {
                    when (signal) {
                        is ShareSignal.Frame -> if (signal.frame.seq > last) {
                            send(signal.frame.event())
                            last = signal.frame.seq
                        }
                        ShareSignal.Ended -> send(ServerSentEvent(event = "e", data = ""))
                    }
                }
            } finally {
                store.unwatch(share, watch)
                store.leave(share)
            }
        }
    }
}

private fun StoredFrame.event() = ServerSentEvent(data = base64Out.encodeToString(bytes), event = if (snapshot) "s" else "u", id = seq.toString())

private suspend fun ApplicationCall.shareId(): String? =
    parameters["id"]?.takeIf(shareId::matches) ?: run {
        respond(HttpStatusCode.NotFound)
        null
    }

private fun ApplicationCall.token(): ByteArray? =
    request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.let { runCatching { base64.decode(it) }.getOrNull() }

/** The request body, or null once it answered 413 for a body over [limit]. */
private suspend fun ApplicationCall.body(limit: Int): ByteArray? {
    val bytes = receiveChannel().readRemaining(limit + 1L).readByteArray()
    if (bytes.size > limit) {
        respond(HttpStatusCode.PayloadTooLarge)
        return null
    }
    return bytes
}

private suspend fun ApplicationCall.answer(result: ShareStore.Result) = when (result) {
    ShareStore.Result.Ok -> respond(HttpStatusCode.NoContent)
    ShareStore.Result.NotFound -> respond(HttpStatusCode.NotFound)
    ShareStore.Result.Forbidden -> respond(HttpStatusCode.Forbidden)
    ShareStore.Result.TooLarge -> respond(HttpStatusCode.PayloadTooLarge)
    ShareStore.Result.Full -> respond(HttpStatusCode.ServiceUnavailable)
}
