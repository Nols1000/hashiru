package com.github.nols1000.hashiru.share.adapter

import com.github.nols1000.hashiru.share.codec.Base64Url
import com.github.nols1000.hashiru.share.port.RelayEvent
import com.github.nols1000.hashiru.share.port.RelayOutcome
import com.github.nols1000.hashiru.share.port.RelayRequest
import com.github.nols1000.hashiru.share.port.ShareRelayPort
import com.github.nols1000.hashiru.share.port.relayOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * HTTP relay client over [transport], sending one request at a time in the order asked. Requests that find no
 * relay are kept and tried again every [RETRY_MS], with [RelayEvent.Unreachable] once until one gets through;
 * waiting for viewers asks the relay every [POLL_MS] in between.
 *
 * All its state belongs to one coroutine in [scope], which takes requests, answers and timers in turn, so
 * [handle] may be called from any thread.
 */
class HttpShareRelay(
    baseUrl: String,
    private val transport: HttpTransport,
    private val scope: CoroutineScope,
) : ShareRelayPort {
    private val baseUrl = baseUrl.trimEnd('/')
    private val _events = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 64)
    override val events: Flow<RelayEvent> = _events

    private val inbox = Channel<Signal>(Channel.UNLIMITED)
    private val pending = mutableListOf<RelayRequest>()
    private val polls = mutableMapOf<String, Poll>()
    private var inFlight: Call? = null
    private var unreachable = false
    private var retryScheduled = false

    init {
        scope.launch { for (signal in inbox) take(signal) }
    }

    override fun handle(request: RelayRequest) {
        inbox.trySend(Signal.Asked(request))
    }

    private fun take(signal: Signal) {
        when (signal) {
            is Signal.Asked -> asked(signal.request)
            is Signal.Answered -> if (signal.call === inFlight) {
                inFlight = null
                answered(signal.request, signal.response)
            }
            is Signal.Polled -> if (signal.call === inFlight) {
                inFlight = null
                polled(signal.poll, signal.response)
            }
            Signal.RetryDue -> {
                retryScheduled = false
                drain()
            }
            is Signal.PollDue -> if (polls[signal.poll.request.shareId] === signal.poll) {
                signal.poll.due = true
                drain()
            }
        }
    }

    private fun asked(request: RelayRequest) {
        // Waiting for viewers outlasts publishes; see RelayRequest.AwaitViewers.
        if (request is RelayRequest.Close) {
            polls.remove(request.shareId)?.call?.let { call ->
                call.job?.cancel()
                if (inFlight === call) inFlight = null
            }
            pending.removeAll { it is RelayRequest.AwaitViewers && it.shareId == request.shareId }
        }
        pending += request
        drain()
    }

    private fun drain() {
        if (inFlight != null) return
        while (pending.isNotEmpty()) {
            val request = pending.removeAt(0)
            if (request is RelayRequest.AwaitViewers) {
                // One standing poll per share; asking again while it stands changes nothing.
                if (request.shareId !in polls) polls[request.shareId] = Poll(request).also { it.due = true }
                continue
            }
            send(request)
            return
        }
        val poll = polls.values.firstOrNull { it.due } ?: return
        poll.due = false
        poll.call = exchange("GET", "/shares/${poll.request.shareId}/watchers", null, poll.request.writeToken) { call, response ->
            Signal.Polled(call, poll, response)
        }
    }

    private fun send(request: RelayRequest) {
        val (method, path, body) = when (request) {
            is RelayRequest.Open -> Triple("PUT", "/shares/${request.shareId}", request.writeToken + request.presenceKey)
            is RelayRequest.Publish -> Triple(
                "PUT",
                "/shares/${request.shareId}/frames/${request.seq}" + if (request.snapshot) "?snapshot=1" else "",
                request.frame,
            )
            is RelayRequest.Close -> Triple("DELETE", "/shares/${request.shareId}", null)
            is RelayRequest.AwaitViewers -> error("AwaitViewers is a standing poll")
        }
        // Opening carries the token in its body; the relay keeps its hash and asks for it as a bearer after.
        val token = request.writeToken.takeUnless { request is RelayRequest.Open }
        exchange(method, path, body, token) { call, response -> Signal.Answered(call, request, response) }
    }

    /** Starts the one exchange in flight; its answer, or null for none, comes back through the inbox. */
    private fun exchange(
        method: String,
        path: String,
        body: ByteArray?,
        writeToken: ByteArray?,
        answer: (Call, HttpResponse?) -> Signal,
    ): Call {
        val call = Call()
        inFlight = call
        call.job = scope.launch {
            val response = try {
                transport.exchange(method, baseUrl + path, body, writeToken?.let(Base64Url::encode))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            inbox.trySend(answer(call, response))
        }
        return call
    }

    private fun answered(request: RelayRequest, response: HttpResponse?) {
        val outcome = response?.let { relayOutcome(request, it.status) } ?: RelayOutcome.Retry
        // A relay that is restarting or overloaded is as far away as one behind a dead network.
        if (outcome == RelayOutcome.Retry) {
            onTransportFailure(request)
            return
        }
        signalReachable()
        if (request !is RelayRequest.Close) {
            when (outcome) {
                RelayOutcome.Done -> when (request) {
                    is RelayRequest.Open -> _events.tryEmit(RelayEvent.Opened(request.shareId))
                    is RelayRequest.Publish -> _events.tryEmit(RelayEvent.Acked(request.shareId, request.seq, response!!.count()))
                    is RelayRequest.Close, is RelayRequest.AwaitViewers -> Unit
                }
                RelayOutcome.Gone -> _events.tryEmit(RelayEvent.Gone(request.shareId))
                RelayOutcome.Refused -> _events.tryEmit(RelayEvent.Refused(request.shareId))
                RelayOutcome.Retry -> Unit
            }
        }
        drain()
    }

    private fun polled(poll: Poll, response: HttpResponse?) {
        if (polls[poll.request.shareId] !== poll) {
            drain()
            return
        }
        // Whatever else the relay answers, the poll stands and asks again: a viewer may yet come.
        when (response?.let { relayOutcome(poll.request, it.status) } ?: RelayOutcome.Retry) {
            RelayOutcome.Done -> {
                signalReachable()
                val count = response!!.count()
                if (count > 0) {
                    // The engine publishes now, and asks again once no one watches.
                    polls.remove(poll.request.shareId)
                    _events.tryEmit(RelayEvent.Watchers(poll.request.shareId, count))
                    drain()
                    return
                }
            }
            RelayOutcome.Gone -> {
                signalReachable()
                polls.remove(poll.request.shareId)
                _events.tryEmit(RelayEvent.Gone(poll.request.shareId))
                drain()
                return
            }
            RelayOutcome.Retry, RelayOutcome.Refused -> Unit
        }
        after(POLL_MS, Signal.PollDue(poll))
        drain()
    }

    private fun onTransportFailure(request: RelayRequest) {
        if (request is RelayRequest.Close) {
            drain()
            return
        }
        pending.add(0, request)
        signalUnreachable()
        if (!retryScheduled) {
            retryScheduled = true
            after(RETRY_MS, Signal.RetryDue)
        }
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

    private fun after(ms: Long, signal: Signal) {
        scope.launch {
            delay(ms)
            inbox.trySend(signal)
        }
    }

    /** The count of open pages the relay answers with. */
    private fun HttpResponse.count(): Int = body.decodeToString().trim().toIntOrNull() ?: 0

    private class Call {
        var job: Job? = null
    }

    private class Poll(val request: RelayRequest.AwaitViewers) {
        var due = false
        var call: Call? = null
    }

    private sealed interface Signal {
        class Asked(val request: RelayRequest) : Signal
        class Answered(val call: Call, val request: RelayRequest, val response: HttpResponse?) : Signal
        class Polled(val call: Call, val poll: Poll, val response: HttpResponse?) : Signal
        data object RetryDue : Signal
        class PollDue(val poll: Poll) : Signal
    }

    private companion object {
        const val POLL_MS = 5_000L
        const val RETRY_MS = 30_000L
    }
}
