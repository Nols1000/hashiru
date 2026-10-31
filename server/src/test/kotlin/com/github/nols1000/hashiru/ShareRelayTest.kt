package com.github.nols1000.hashiru

import com.github.nols1000.hashiru.share.ShareSignal
import com.github.nols1000.hashiru.share.ShareStore
import com.github.nols1000.hashiru.share.StoredFrame
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareRelayTest {
    private val id = "AAAAAAAAAAAAAAAAAAAAAA"
    private val token = ByteArray(32) { 1 }
    private val presence = ByteArray(32) { 2 }
    private val bearer = Base64.getUrlEncoder().withoutPadding().encodeToString(token)
    private var now = 1_700_000_000_000L
    private val store = ShareStore(nowMs = { now })

    private fun relay(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(store) }
        test()
    }

    private suspend fun ApplicationTestBuilder.open() =
        client.put("/api/shares/$id") { setBody(token + presence) }.status

    private suspend fun ApplicationTestBuilder.publish(seq: Long, bytes: ByteArray, snapshot: Boolean = false) =
        client.put("/api/shares/$id/frames/$seq${if (snapshot) "?snapshot=1" else ""}") {
            bearerAuth(bearer)
            setBody(bytes)
        }

    private fun proof(nonce: String, key: ByteArray = presence): String {
        val mac = Mac.getInstance("HmacSHA512").run {
            init(SecretKeySpec(key, "HmacSHA512"))
            doFinal(nonce.encodeToByteArray())
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac)
    }

    private suspend fun ApplicationTestBuilder.proofUrl(key: ByteArray = presence): String {
        val nonce = client.get("/api/shares/$id/challenge").bodyAsText()
        return "/api/shares/$id/stream?nonce=$nonce&proof=${proof(nonce, key)}"
    }

    @Test
    fun onlyTheWalkerCanPublish() = relay {
        assertEquals(HttpStatusCode.NoContent, open())
        // Opening again after a restart of the phone is fine, with the same token only.
        assertEquals(HttpStatusCode.NoContent, open())
        assertEquals(HttpStatusCode.Forbidden, client.put("/api/shares/$id") { setBody(ByteArray(32) + presence) }.status)

        val forged = client.put("/api/shares/$id/frames/1") {
            bearerAuth(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)))
            setBody(byteArrayOf(1))
        }
        assertEquals(HttpStatusCode.Forbidden, forged.status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/shares/$id/frames/1") { setBody(byteArrayOf(1)) }.status)
        assertEquals("0", publish(1, byteArrayOf(1), snapshot = true).bodyAsText())
    }

    @Test
    fun anUnknownShareIsGoneSoThePhoneOpensItAgain() = relay {
        assertEquals(HttpStatusCode.NotFound, publish(1, byteArrayOf(1)).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/shares/$id/challenge").status)
    }

    @Test
    fun viewersGetTheSnapshotThenWhatFollowsAndAreCounted() {
        // The in-memory test engine holds streamed responses back until they end, so this runs on Netty.
        val server = embeddedServer(Netty, port = 0) { module(store) }.start(wait = false)
        try {
            val base = runBlocking { "http://localhost:${server.engine.resolvedConnectors().first().port}" }
            val http = HttpClient.newHttpClient()
            fun call(method: String, path: String, body: ByteArray = ByteArray(0), auth: Boolean = false): String {
                val request = HttpRequest.newBuilder(URI("$base$path")).method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                if (auth) request.header("Authorization", "Bearer $bearer")
                return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body()
            }
            call("PUT", "/api/shares/$id", token + presence)
            call("PUT", "/api/shares/$id/frames/5?snapshot=1", byteArrayOf(5), auth = true)
            call("PUT", "/api/shares/$id/frames/6", byteArrayOf(6), auth = true)
            val nonce = call("GET", "/api/shares/$id/challenge")
            val stream = http.send(
                HttpRequest.newBuilder(URI("$base/api/shares/$id/stream?nonce=$nonce&proof=${proof(nonce)}")).build(),
                HttpResponse.BodyHandlers.ofLines(),
            ).body().iterator()

            fun nextEvent(): Pair<String, String> {
                var event = ""
                var eventId = ""
                while (true) {
                    val line = stream.next()
                    when {
                        line.startsWith("event: ") -> event = line.removePrefix("event: ")
                        line.startsWith("id: ") -> eventId = line.removePrefix("id: ")
                        line.isEmpty() && event.isNotEmpty() -> return event to eventId
                    }
                }
            }
            assertEquals("s" to "5", nextEvent())
            assertEquals("u" to "6", nextEvent())
            assertEquals("1", call("GET", "/api/shares/$id/watchers", auth = true))
            assertEquals("1", call("PUT", "/api/shares/$id/frames/7", byteArrayOf(7), auth = true))
            assertEquals("u" to "7", nextEvent())
            call("DELETE", "/api/shares/$id", auth = true)
            assertEquals("e" to "", nextEvent())
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun aStalledViewerIsClosedWithoutCostingTheOthersFrames() = runBlocking {
        store.open(id, token, presence)
        val nonce = store.challenge(id)!!
        val share = store.admit(id, nonce, Base64.getUrlDecoder().decode(proof(nonce)))!!
        val stalled = store.watch(share)
        val reading = store.watch(share)

        val count = store.limits.maxBehind * 2L
        for (seq in 1..count) {
            assertEquals(ShareStore.Result.Ok, store.publish(id, token, StoredFrame(seq, false, byteArrayOf(seq.toByte()))))
            assertEquals(seq, (reading.signals.receive() as ShareSignal.Frame).frame.seq)
        }
        store.close(id, token)
        assertEquals(ShareSignal.Ended, reading.signals.receive())

        // The stalled viewer keeps what fit, then its stream ends; reconnecting, it gets every frame and the end.
        var kept = 0
        for (signal in stalled.signals) kept++
        assertEquals(store.limits.maxBehind, kept)
        val again = store.watch(share)
        assertEquals((1..count).toList(), again.held.map { it.seq })
        assertTrue(again.ended)
    }

    @Test
    fun aViewerWithoutTheLinkIsNotCounted() = relay {
        val sse = createClient { install(SSE) }
        open()
        val url = proofUrl(key = ByteArray(32) { 9 })

        var first: ServerSentEvent? = null
        sse.sse(url) { incoming.collect { first = it } }

        assertEquals("denied", first?.event)
        assertEquals("0", client.get("/api/shares/$id/watchers") { bearerAuth(bearer) }.bodyAsText())
    }

    @Test
    fun aChallengeIsSpentOnce() {
        store.open(id, token, presence)
        val nonce = store.challenge(id)!!
        val proof = Base64.getUrlDecoder().decode(proof(nonce))

        assertEquals(id, store.admit(id, nonce, proof)?.id)
        assertEquals(null, store.admit(id, nonce, proof))
    }

    @Test
    fun aStaleChallengeIsRefused() {
        store.open(id, token, presence)
        val nonce = store.challenge(id)!!
        now += store.limits.challengeMs + 1

        assertEquals(null, store.admit(id, nonce, Base64.getUrlDecoder().decode(proof(nonce))))
    }

    @Test
    fun oversizedFramesAreRefused() = relay {
        open()
        assertEquals(HttpStatusCode.PayloadTooLarge, publish(1, ByteArray(store.limits.maxFrameBytes + 1)).status)
        assertEquals(HttpStatusCode.NoContent.value / 100, publish(2, ByteArray(store.limits.maxFrameBytes)).status.value / 100)
    }

    @Test
    fun aFullRelaySaysSoAndToTryLater() = testApplication {
        application { module(ShareStore(nowMs = { now }, limits = ShareStore.Limits(maxShares = 1))) }
        assertEquals(HttpStatusCode.NoContent, open())

        val full = client.put("/api/shares/BBBBBBBBBBBBBBBBBBBBBB") { setBody(token + presence) }
        assertEquals(HttpStatusCode.ServiceUnavailable, full.status)
        assertEquals("full", full.bodyAsText())
        assertEquals("60", full.headers[HttpHeaders.RetryAfter])
        // The share it holds is still welcome.
        assertEquals(HttpStatusCode.NoContent, open())
    }

    @Test
    fun endedSharesAreForgottenAfterAWhile() = relay {
        open()
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/shares/$id") { bearerAuth(bearer) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/shares/$id/challenge").status)

        now += store.limits.keepEndedMs + 1
        store.sweep()

        assertEquals(HttpStatusCode.NotFound, client.get("/api/shares/$id/challenge").status)
    }
}
