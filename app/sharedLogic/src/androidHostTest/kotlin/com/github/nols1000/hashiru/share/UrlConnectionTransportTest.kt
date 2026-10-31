package com.github.nols1000.hashiru.share

import com.github.nols1000.hashiru.share.adapter.UrlConnectionTransport
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UrlConnectionTransportTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val transport = UrlConnectionTransport(connectTimeoutMs = 2_000, readTimeoutMs = 5_000)

    @AfterTest
    fun stop() = server.stop(0)

    private fun answer(path: String, handler: (HttpExchange) -> Unit) = server.createContext(path) { exchange ->
        exchange.use(handler)
    }

    private fun HttpExchange.reply(status: Int, body: ByteArray = ByteArray(0)) {
        sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.write(body)
    }

    @Test
    fun sendsTheMethodBodyAndToken() = runBlocking {
        val seen = CompletableDeferred<Triple<String, String?, ByteArray>>()
        answer("/shares/abc") {
            seen.complete(Triple(it.requestMethod, it.requestHeaders.getFirst("Authorization"), it.requestBody.readBytes()))
            it.reply(200, byteArrayOf(7, 8))
        }

        val response = transport.exchange("PUT", "$base/shares/abc", byteArrayOf(1, 2, 3), "token")

        assertEquals(200, response.status)
        assertContentEquals(byteArrayOf(7, 8), response.body)
        val (method, authorization, body) = seen.await()
        assertEquals("PUT", method)
        assertEquals("Bearer token", authorization)
        assertContentEquals(byteArrayOf(1, 2, 3), body)
    }

    @Test
    fun sendsNoTokenOrBodyWhereNoneIsGiven() = runBlocking {
        val seen = CompletableDeferred<Pair<String?, Int>>()
        answer("/shares/abc") {
            seen.complete(it.requestHeaders.getFirst("Authorization") to it.requestBody.readBytes().size)
            it.reply(204)
        }

        val response = transport.exchange("DELETE", "$base/shares/abc", null, null)

        assertEquals(204, response.status)
        assertEquals(0, response.body.size)
        assertEquals(null to 0, seen.await())
    }

    @Test
    fun readsTheBodyOfAnError() = runBlocking {
        answer("/shares/gone") { it.reply(404, "no such share".encodeToByteArray()) }

        val response = transport.exchange("GET", "$base/shares/gone", null, "token")

        assertEquals(404, response.status)
        assertEquals("no such share", response.body.decodeToString())
    }

    @Test
    fun answersAServerErrorWithoutABody() = runBlocking {
        answer("/busy") { it.reply(503) }

        assertEquals(503, transport.exchange("GET", "$base/busy", null, null).status)
    }

    @Test
    fun throwsWithoutARelay() = runBlocking {
        server.stop(0)

        assertFailsWith<Exception> { transport.exchange("GET", "$base/shares/abc", null, null) }
        Unit
    }

    @Test
    fun cancellingAbandonsAnExchangeTheRelayHoldsOpen() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        answer("/slow") {
            arrived.countDown()
            release.await(10, TimeUnit.SECONDS)
            it.reply(200)
        }
        try {
            val call = async(Dispatchers.Default) { transport.exchange("GET", "$base/slow", null, null) }
            assertTrue(arrived.await(5, TimeUnit.SECONDS))

            // Well before the read timeout.
            withTimeout(2_000) {
                call.cancel()
                call.join()
            }
            assertTrue(call.isCancelled)
        } finally {
            release.countDown()
        }
    }
}
