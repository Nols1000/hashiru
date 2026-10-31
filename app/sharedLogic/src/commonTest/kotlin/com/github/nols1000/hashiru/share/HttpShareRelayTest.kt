package com.github.nols1000.hashiru.share

import com.github.nols1000.hashiru.share.adapter.HttpResponse
import com.github.nols1000.hashiru.share.adapter.HttpShareRelay
import com.github.nols1000.hashiru.share.adapter.HttpTransport
import com.github.nols1000.hashiru.share.codec.Base64Url
import com.github.nols1000.hashiru.share.port.RelayEvent
import com.github.nols1000.hashiru.share.port.RelayRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HttpShareRelayTest {
    private val token = ByteArray(32) { it.toByte() }
    private val presence = ByteArray(32) { (100 + it).toByte() }
    private val transport = FakeTransport()
    private val events = mutableListOf<RelayEvent>()

    private fun open(id: String = "s") = RelayRequest.Open(id, token, presence)
    private fun publish(seq: Long, id: String = "s", snapshot: Boolean = false) =
        RelayRequest.Publish(id, token, seq, byteArrayOf(seq.toByte()), snapshot)
    private fun await(id: String = "s") = RelayRequest.AwaitViewers(id, token)
    private fun close(id: String = "s") = RelayRequest.Close(id, token)

    private fun TestScope.relay(): HttpShareRelay {
        val relay = HttpShareRelay("https://relay.test/api/", transport, backgroundScope)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { relay.events.collect { events += it } }
        return relay
    }

    /** Lets the relay take everything asked so far. */
    private fun TestScope.settle() = runCurrent()

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private val calls get() = transport.calls
    private val last get() = calls.last()

    @Test
    fun requestsGoOneAtATimeInOrder() = runTest {
        val relay = relay()
        relay.handle(open())
        relay.handle(publish(1, snapshot = true))
        relay.handle(publish(2))
        relay.handle(close())
        settle()

        assertEquals(1, calls.size)
        assertEquals("PUT", last.method)
        assertEquals("https://relay.test/api/shares/s", last.url)
        // Opening hands the relay the token and presence key in its body, not as a bearer.
        assertContentEquals(token + presence, last.body)
        assertNull(last.bearerToken)
        last.reply(204)
        settle()
        assertEquals(listOf<RelayEvent>(RelayEvent.Opened("s")), events)

        assertEquals(2, calls.size)
        assertEquals("PUT", last.method)
        assertEquals("https://relay.test/api/shares/s/frames/1?snapshot=1", last.url)
        assertContentEquals(byteArrayOf(1), last.body)
        assertEquals(Base64Url.encode(token), last.bearerToken)
        last.reply(200, "3\n")
        settle()

        assertEquals("https://relay.test/api/shares/s/frames/2", last.url)
        last.reply(200, "0")
        settle()

        assertEquals("DELETE", last.method)
        assertEquals("https://relay.test/api/shares/s", last.url)
        assertNull(last.body)
        assertEquals(Base64Url.encode(token), last.bearerToken)
        last.reply(204)
        settle()

        assertEquals(4, calls.size)
        assertEquals(
            listOf(RelayEvent.Opened("s"), RelayEvent.Acked("s", 1, 3), RelayEvent.Acked("s", 2, 0)),
            events,
        )
    }

    @Test
    fun aFailingRelayIsTriedAgainAfterTheBackoff() = runTest {
        val relay = relay()
        relay.handle(publish(1))
        relay.handle(publish(2))
        settle()
        calls.single().fail()
        settle()
        assertEquals(listOf<RelayEvent>(RelayEvent.Unreachable), events)

        wait(29_999)
        assertEquals(1, calls.size)
        wait(1)
        assertEquals(2, calls.size)
        assertTrue(last.url.endsWith("/frames/1"))
        // A relay that is redeploying is as far away as one behind a dead network, and said so once.
        last.reply(503)
        settle()
        assertEquals(listOf<RelayEvent>(RelayEvent.Unreachable), events)

        wait(30_000)
        assertTrue(last.url.endsWith("/frames/1"))
        last.reply(200, "1")
        settle()
        assertEquals(listOf(RelayEvent.Unreachable, RelayEvent.Reachable, RelayEvent.Acked("s", 1, 1)), events)
        assertTrue(last.url.endsWith("/frames/2"))
        assertEquals(4, calls.size)
    }

    @Test
    fun aFailedCloseIsDroppedQuietly() = runTest {
        val relay = relay()
        relay.handle(close())
        relay.handle(close("t"))
        relay.handle(open("u"))
        settle()
        last.fail()
        settle()
        assertEquals("https://relay.test/api/shares/t", last.url)
        last.reply(502)
        settle()
        assertEquals("https://relay.test/api/shares/u", last.url)
        assertEquals(emptyList(), events)

        wait(60_000)
        assertEquals(3, calls.size)
    }

    @Test
    fun aForgottenShareIsGone() = runTest {
        val relay = relay()
        relay.handle(publish(1))
        settle()
        last.reply(404)
        settle()
        assertEquals(listOf<RelayEvent>(RelayEvent.Gone("s")), events)

        relay.handle(await())
        settle()
        assertEquals("https://relay.test/api/shares/s/watchers", last.url)
        last.reply(401)
        settle()
        assertEquals(listOf<RelayEvent>(RelayEvent.Gone("s"), RelayEvent.Gone("s")), events)
        // The poll ends with the share.
        wait(60_000)
        assertEquals(2, calls.size)
    }

    @Test
    fun thePollStandsThroughPublishesAndOutagesUntilSomeoneWatches() = runTest {
        val relay = relay()
        relay.handle(await())
        settle()
        assertEquals("GET", last.method)
        assertEquals("https://relay.test/api/shares/s/watchers", last.url)
        assertNull(last.body)
        assertEquals(Base64Url.encode(token), last.bearerToken)
        last.reply(200, "0")
        settle()
        assertEquals(1, calls.size)

        // A publish asked between polls goes first; the poll waits its turn.
        wait(4_000)
        relay.handle(publish(1))
        settle()
        assertTrue(last.url.endsWith("/frames/1"))
        last.reply(200, "0")
        settle()
        wait(1_000)
        assertTrue(last.url.endsWith("/watchers"))
        // An outage keeps it standing, and tells nothing of the relay's reach.
        last.fail()
        settle()
        wait(5_000)
        assertEquals(4, calls.size)
        last.reply(503)
        settle()
        wait(5_000)
        assertEquals(5, calls.size)
        last.reply(200, "2")
        settle()

        assertEquals(listOf(RelayEvent.Acked("s", 1, 0), RelayEvent.Watchers("s", 2)), events)
        wait(60_000)
        assertEquals(5, calls.size)
    }

    @Test
    fun askingAgainWhileThePollStandsChangesNothing() = runTest {
        val relay = relay()
        relay.handle(await())
        relay.handle(await())
        settle()
        assertEquals(1, calls.size)
        relay.handle(await())
        last.reply(200, "0")
        settle()
        relay.handle(await())
        settle()
        assertEquals(1, calls.size)

        wait(5_000)
        assertEquals(2, calls.size)
        last.reply(200, "0")
        settle()
        wait(4_999)
        assertEquals(2, calls.size)
        wait(1)
        assertEquals(3, calls.size)
    }

    @Test
    fun closingEndsThePoll() = runTest {
        val relay = relay()
        relay.handle(await())
        settle()
        val poll = last
        relay.handle(close())
        settle()

        assertTrue(poll.cancelled)
        assertEquals("DELETE", last.method)
        last.reply(204)
        settle()
        wait(60_000)
        assertEquals(2, calls.size)
        assertEquals(emptyList(), events)
    }

    @Test
    fun closingDropsAPollNotYetAsked() = runTest {
        val relay = relay()
        relay.handle(publish(1))
        relay.handle(await())
        relay.handle(close())
        settle()
        last.reply(200, "0")
        settle()
        assertEquals("DELETE", last.method)
        last.reply(204)
        settle()
        wait(60_000)
        assertEquals(2, calls.size)
    }

    @Test
    fun aShareTheRelayWillNotTakeIsRefused() = runTest {
        val relay = relay()
        relay.handle(open())
        relay.handle(publish(1))
        settle()
        last.reply(403)
        settle()
        last.reply(413)
        settle()
        assertEquals(listOf<RelayEvent>(RelayEvent.Refused("s"), RelayEvent.Refused("s")), events)
        wait(60_000)
        assertEquals(2, calls.size)
    }

    private class FakeTransport : HttpTransport {
        val calls = mutableListOf<Call>()

        override suspend fun exchange(method: String, url: String, body: ByteArray?, bearerToken: String?): HttpResponse {
            val call = Call(method, url, body, bearerToken)
            calls += call
            try {
                return call.answer.await()
            } catch (e: CancellationException) {
                call.cancelled = true
                throw e
            }
        }
    }

    private class Call(val method: String, val url: String, val body: ByteArray?, val bearerToken: String?) {
        val answer = CompletableDeferred<HttpResponse>()
        var cancelled = false

        fun reply(status: Int, body: String = "") {
            answer.complete(HttpResponse(status, body.encodeToByteArray()))
        }

        /** No answer comes, as without a network. */
        fun fail() {
            answer.completeExceptionally(IllegalStateException("offline"))
        }
    }
}
