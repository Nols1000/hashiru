package com.github.nols1000.hashiru.share

import com.github.nols1000.hashiru.share.adapter.UrlSessionTransport
import com.github.nols1000.hashiru.toByteArray
import com.github.nols1000.hashiru.toNSData
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPBodyStream
import platform.Foundation.HTTPMethod
import platform.Foundation.NSCachedURLResponse
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSInputStream
import platform.Foundation.NSLock
import platform.Foundation.NSURLCacheStoragePolicy
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLProtocol
import platform.Foundation.NSURLProtocolClientProtocol
import platform.Foundation.NSURLProtocolMeta
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.valueForHTTPHeaderField
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(BetaInteropApi::class)
class UrlSessionTransportTest {
    private val transport = UrlSessionTransport(
        NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
            protocolClasses = listOf(StubProtocol.`class`())
        },
    )

    @BeforeTest
    fun reset() = StubNetwork.reset()

    @AfterTest
    fun tidy() = StubNetwork.reset()

    @Test
    fun sendsTheMethodUrlTokenAndBody() = runTest {
        StubNetwork.answer = StubNetwork.Answer.Response(201, ByteArray(0))
        val body = byteArrayOf(0, 1, 2, -1, 127, -128)

        transport.exchange("PUT", "https://relay.test/api/s/abc", body, "tok3n")

        val seen = StubNetwork.requests.single()
        assertEquals("PUT", seen.method)
        assertEquals("https://relay.test/api/s/abc", seen.url)
        assertEquals("Bearer tok3n", seen.authorization)
        assertContentEquals(body, seen.body)
    }

    @Test
    fun sendsNoAuthorizationWithoutAToken() = runTest {
        StubNetwork.answer = StubNetwork.Answer.Response(200, ByteArray(0))

        transport.exchange("GET", "https://relay.test/api/s/abc/viewers", null, null)

        val seen = StubNetwork.requests.single()
        assertEquals("GET", seen.method)
        assertNull(seen.authorization)
        assertEquals(0, seen.body.size)
    }

    @Test
    fun answersWithTheStatusAndBody() = runTest {
        StubNetwork.answer = StubNetwork.Answer.Response(409, "taken".encodeToByteArray())

        val response = transport.exchange("POST", "https://relay.test/api/s", byteArrayOf(1), "t")

        assertEquals(409, response.status)
        assertEquals("taken", response.body.decodeToString())
    }

    @Test
    fun throwsWhenTheNetworkFails() = runTest {
        StubNetwork.answer = StubNetwork.Answer.Failure

        assertFailsWith<IllegalStateException> {
            transport.exchange("GET", "https://relay.test/api/s", null, null)
        }
    }

    @Test
    fun cancellingStopsTheTask() = runTest {
        StubNetwork.answer = StubNetwork.Answer.Silence
        val exchange = async(start = CoroutineStart.UNDISPATCHED) {
            transport.exchange("GET", "https://relay.test/api/s/abc/viewers", null, "t")
        }
        awaitReally { StubNetwork.requests.isNotEmpty() }

        exchange.cancel()

        // The session stops the load, then calls the completion handler with NSURLErrorCancelled; resuming a second
        // time from there would throw on the session's queue and take the test process with it.
        awaitReally { StubNetwork.stops > 0 }
        withContext(Dispatchers.Default) { delay(200) }
        assertTrue(exchange.isCancelled)
    }

    /** Waits in real time, as the session answers from a queue of its own, until [condition] holds. */
    private suspend fun awaitReally(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }
}

/** What the stubbed network saw and how it answers; the session's queue and the test both reach it. */
private object StubNetwork {
    sealed interface Answer {
        class Response(val status: Int, val body: ByteArray) : Answer
        data object Failure : Answer
        data object Silence : Answer
    }

    class Seen(val method: String?, val url: String?, val authorization: String?, val body: ByteArray)

    private val lock = NSLock()
    private var _answer: Answer = Answer.Silence
    private val _requests = mutableListOf<Seen>()
    private var _stops = 0

    var answer: Answer
        get() = locked { _answer }
        set(value) = locked { _answer = value }
    val requests: List<Seen> get() = locked { _requests.toList() }
    val stops: Int get() = locked { _stops }

    fun record(seen: Seen) = locked { _requests += seen }
    fun stopped() = locked { _stops++ }
    fun reset() = locked {
        _answer = Answer.Silence
        _requests.clear()
        _stops = 0
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/** Takes every request the session makes, records it, and answers with [StubNetwork.answer]. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class StubProtocol @kotlinx.cinterop.ObjCObjectBase.OverrideInit constructor(
    request: NSURLRequest,
    cachedResponse: NSCachedURLResponse?,
    client: NSURLProtocolClientProtocol?,
) : NSURLProtocol(request, cachedResponse, client) {
    companion object : NSURLProtocolMeta() {
        override fun canInitWithRequest(request: NSURLRequest): Boolean = true
        override fun canonicalRequestForRequest(request: NSURLRequest): NSURLRequest = request
    }

    override fun startLoading() {
        val request = request
        StubNetwork.record(
            StubNetwork.Seen(
                method = request.HTTPMethod,
                url = request.URL?.absoluteString,
                authorization = request.valueForHTTPHeaderField("Authorization"),
                // The session hands the body over as a stream rather than as data.
                body = request.HTTPBody?.toByteArray() ?: request.HTTPBodyStream?.readAll() ?: ByteArray(0),
            ),
        )
        val client = client ?: return
        when (val answer = StubNetwork.answer) {
            is StubNetwork.Answer.Response -> {
                val response = NSHTTPURLResponse(request.URL!!, answer.status.toLong(), "HTTP/1.1", null)
                client.URLProtocol(this, didReceiveResponse = response, cacheStoragePolicy = NSURLCacheStoragePolicy.NSURLCacheStorageNotAllowed)
                // An empty array has no first byte to pin for toNSData.
                if (answer.body.isNotEmpty()) client.URLProtocol(this, didLoadData = answer.body.toNSData())
                client.URLProtocolDidFinishLoading(this)
            }
            StubNetwork.Answer.Failure -> client.URLProtocol(
                this,
                didFailWithError = NSError.errorWithDomain(NSURLErrorDomain, NSURLErrorNotConnectedToInternet, null),
            )
            StubNetwork.Answer.Silence -> Unit
        }
    }

    override fun stopLoading() {
        StubNetwork.stopped()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSInputStream.readAll(): ByteArray {
    open()
    val chunk = ByteArray(4096)
    var all = ByteArray(0)
    while (true) {
        val read = chunk.usePinned { read(it.addressOf(0).reinterpret(), chunk.size.toULong()) }
        if (read <= 0) break
        all += chunk.copyOf(read.toInt())
    }
    close()
    return all
}
