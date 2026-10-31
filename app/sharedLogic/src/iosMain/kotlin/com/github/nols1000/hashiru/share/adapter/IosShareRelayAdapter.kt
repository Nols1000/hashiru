package com.github.nols1000.hashiru.share.adapter

import com.github.nols1000.hashiru.share.codec.Base64Url
import com.github.nols1000.hashiru.iosinterop.hashiruSetHTTPBody
import com.github.nols1000.hashiru.iosinterop.hashiruDataTaskWithRequest
import com.github.nols1000.hashiru.iosinterop.hashiruSetHTTPHeaderField
import com.github.nols1000.hashiru.iosinterop.hashiruSetHTTPMethod
import com.github.nols1000.hashiru.share.port.RelayEvent
import com.github.nols1000.hashiru.share.port.RelayRequest
import com.github.nols1000.hashiru.share.port.ShareRelayPort
import com.github.nols1000.hashiru.toByteArray
import com.github.nols1000.hashiru.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataTask
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_time

/** HTTP relay client. All request, retry, and polling state is confined to [queue]. */
@OptIn(ExperimentalForeignApi::class)
class IosShareRelayAdapter(baseUrl: String) : ShareRelayPort {
    private val baseUrl = baseUrl.trimEnd('/')
    private val queue = dispatch_queue_create("hashiru.share.relay", null)
    private val session = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration(),
        null,
        null,
    )
    private val _events = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 64)
    override val events: Flow<RelayEvent> = _events

    private val pending = mutableListOf<RelayRequest>()
    private val polls = mutableMapOf<String, Poll>()
    private var inFlight = false
    private var unreachable = false
    private var retryScheduled = false

    override fun handle(request: RelayRequest) {
        dispatch_async(queue) {
            if (request is RelayRequest.Publish || request is RelayRequest.Close) {
                polls.remove(request.shareId)?.task?.cancel()
                pending.removeAll { it is RelayRequest.AwaitViewers && it.shareId == request.shareId }
            }
            pending += request
            drain()
        }
    }

    private fun drain() {
        if (inFlight) return
        while (pending.isNotEmpty()) {
            val request = pending.removeAt(0)
            if (request is RelayRequest.AwaitViewers) {
                polls.getOrPut(request.shareId) { Poll(request) }.due = true
                continue
            }
            send(request)
            return
        }
        val poll = polls.values.firstOrNull { it.due } ?: return
        poll.due = false
        sendPoll(poll)
    }

    private fun send(request: RelayRequest) {
        val path = when (request) {
            is RelayRequest.Open -> "/shares/${request.shareId}"
            is RelayRequest.Publish -> "/shares/${request.shareId}/frames/${request.seq}" + if (request.snapshot) "?snapshot=1" else ""
            is RelayRequest.Close -> "/shares/${request.shareId}"
            is RelayRequest.AwaitViewers -> error("AwaitViewers is a standing poll")
        }
        val method = when (request) {
            is RelayRequest.Open, is RelayRequest.Publish -> "PUT"
            is RelayRequest.Close -> "DELETE"
            is RelayRequest.AwaitViewers -> error("AwaitViewers is a standing poll")
        }
        val body = when (request) {
            is RelayRequest.Open -> ByteArray(request.writeToken.size + request.presenceKey.size).also {
                request.writeToken.copyInto(it)
                request.presenceKey.copyInto(it, request.writeToken.size)
            }
            is RelayRequest.Publish -> request.frame
            is RelayRequest.Close -> null
            is RelayRequest.AwaitViewers -> error("AwaitViewers is a standing poll")
        }
        val url = NSURL(string = baseUrl + path)
        val urlRequest = makeRequest(url, method, body, request.writeToken.takeUnless { request is RelayRequest.Open })
        val task = hashiruDataTaskWithRequest(session, urlRequest) { data, response, error ->
            dispatch_async(queue) {
                completeRequest(request, response, error, data?.toByteArray() ?: ByteArray(0))
            }
        } ?: run {
            onTransportFailure(request)
            return
        }
        inFlight = true
        task.resume()
    }

    private fun sendPoll(poll: Poll) {
        val url = NSURL(string = "$baseUrl/shares/${poll.request.shareId}/watchers")
        val request = makeRequest(url, "GET", writeToken = poll.request.writeToken)
        val task = hashiruDataTaskWithRequest(session, request) { data, response, error ->
            dispatch_async(queue) {
                completePoll(poll, response, error, data?.toByteArray() ?: ByteArray(0))
            }
        } ?: run {
            schedulePoll(poll)
            return
        }
        poll.task = task
        inFlight = true
        task.resume()
    }

    private fun makeRequest(url: NSURL, method: String, body: ByteArray? = null, writeToken: ByteArray? = null): NSMutableURLRequest {
        val request = NSMutableURLRequest.requestWithURL(url)
        hashiruSetHTTPMethod(request, method)
        body?.let { hashiruSetHTTPBody(request, it.toNSData()) }
        writeToken?.let { hashiruSetHTTPHeaderField(request, "Bearer ${Base64Url.encode(it)}", "Authorization") }
        return request
    }


    private fun completeRequest(request: RelayRequest, response: NSURLResponse?, error: NSError?, data: ByteArray) {
        inFlight = false
        val http = response as? NSHTTPURLResponse
        if (error != null || http == null) {
            if (request is RelayRequest.Close) {
                drain()
            } else {
                pending.add(0, request)
                signalUnreachable()
                scheduleRetry()
            }
            return
        }
        signalReachable()
        when (request) {
            is RelayRequest.Open -> {
                if (http.statusCode.toInt() in 200..299) _events.tryEmit(RelayEvent.Opened(request.shareId))
                else _events.tryEmit(RelayEvent.Refused(request.shareId))
            }
            is RelayRequest.Publish -> when (http.statusCode.toInt()) {
                in 200..299 -> {
                    val watchers = data.decodeToString().trim().toIntOrNull() ?: 0
                    _events.tryEmit(RelayEvent.Acked(request.shareId, request.seq, watchers))
                }
                401, 403, 404 -> _events.tryEmit(RelayEvent.Gone(request.shareId))
                else -> _events.tryEmit(RelayEvent.Refused(request.shareId))
            }
            is RelayRequest.Close, is RelayRequest.AwaitViewers -> Unit
        }
        drain()
    }

    private fun completePoll(poll: Poll, response: NSURLResponse?, error: NSError?, data: ByteArray) {
        inFlight = false
        if (polls[poll.request.shareId] !== poll) {
            drain()
            return
        }
        val http = response as? NSHTTPURLResponse
        if (error == null && http != null) {
            val status = http.statusCode.toInt()
            if (status in 200..299) {
                signalReachable()
                data.decodeToString().trim().toIntOrNull()?.let { count ->
                    if (poll.lastCount != count) {
                        poll.lastCount = count
                        _events.tryEmit(RelayEvent.Watchers(poll.request.shareId, count))
                    }
                }
            } else if (status == 401 || status == 403 || status == 404) {
                signalReachable()
                polls.remove(poll.request.shareId)
                _events.tryEmit(RelayEvent.Gone(poll.request.shareId))
                drain()
                return
            }
        }
        schedulePoll(poll)
        drain()
    }


    private fun schedulePoll(poll: Poll) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (POLL_SECONDS.toULong() * NSEC_PER_SEC).toLong()), queue) {
            if (polls[poll.request.shareId] === poll) {
                poll.due = true
                drain()
            }
        }
    }

    private fun onTransportFailure(request: RelayRequest) {
        if (request is RelayRequest.Close) {
            drain()
            return
        }
        pending.add(0, request)
        signalUnreachable()
        scheduleRetry()
    }

    private fun signalUnreachable() {
        if (!unreachable) {
            unreachable = true
            _events.tryEmit(RelayEvent.Unreachable)
        }
    }

    private fun signalReachable() {
        if (unreachable) {
            unreachable = false
            _events.tryEmit(RelayEvent.Reachable)
        }
    }

    private fun scheduleRetry() {
        if (retryScheduled) return
        retryScheduled = true
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (RETRY_SECONDS.toULong() * NSEC_PER_SEC).toLong()), queue) {
            retryScheduled = false
            drain()
        }
    }


    private class Poll(val request: RelayRequest.AwaitViewers) {
        var due = false
        var lastCount: Int? = null
        var task: NSURLSessionDataTask? = null
    }

    private companion object {
        const val POLL_SECONDS = 5L
        const val RETRY_SECONDS = 30L
    }
}
