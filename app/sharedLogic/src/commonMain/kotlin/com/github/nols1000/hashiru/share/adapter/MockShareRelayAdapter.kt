package com.github.nols1000.hashiru.share.adapter

import com.github.nols1000.hashiru.share.port.RelayEvent
import com.github.nols1000.hashiru.share.port.RelayRequest
import com.github.nols1000.hashiru.share.port.ShareRelayPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Relay kept in memory, answering at once. It reports [watchers] pages open on every share; set [reachable]
 * to false to have requests fail as without a network, and call [arrive] or [leave] to have a viewer open or
 * close the link. Like a real adapter, it tells of an arrival only while it waits for viewers; otherwise the
 * count shows up in the next ack.
 */
class MockShareRelayAdapter(var watchers: Int = 0) : ShareRelayPort {
    private val _events = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 64)
    override val events: Flow<RelayEvent> = _events

    private val _requests = mutableListOf<RelayRequest>()

    /** Everything asked of the relay, in order, including requests that failed. */
    val requests: List<RelayRequest> get() = _requests

    /** Frames the relay holds for each share, by counter: the last snapshot and what followed it. */
    val frames = mutableMapOf<String, MutableMap<Long, RelayRequest.Publish>>()
    val closed = mutableSetOf<String>()

    /** Shares waiting for viewers, as asked by [RelayRequest.AwaitViewers]. */
    val awaiting = mutableSetOf<String>()

    var reachable = true
        set(value) {
            if (field == value) return
            field = value
            _events.tryEmit(if (value) RelayEvent.Reachable else RelayEvent.Unreachable)
        }

    override fun handle(request: RelayRequest) {
        _requests += request
        // Waiting for viewers outlasts an outage, as a real adapter keeps asking.
        when (request) {
            is RelayRequest.AwaitViewers -> awaiting += request.shareId
            is RelayRequest.Close -> awaiting -= request.shareId
            else -> Unit
        }
        if (!reachable) return
        when (request) {
            is RelayRequest.Open -> _events.tryEmit(RelayEvent.Opened(request.shareId))
            is RelayRequest.Publish -> {
                val held = frames.getOrPut(request.shareId) { mutableMapOf() }
                if (request.snapshot) held.keys.removeAll { it < request.seq }
                held[request.seq] = request
                _events.tryEmit(RelayEvent.Acked(request.shareId, request.seq, watchers))
            }
            is RelayRequest.AwaitViewers -> Unit
            is RelayRequest.Close -> closed += request.shareId
        }
    }

    /** A viewer opens [shareId]'s link. */
    fun arrive(shareId: String) {
        watchers++
        if (awaiting.remove(shareId)) _events.tryEmit(RelayEvent.Watchers(shareId, watchers))
    }

    /** A viewer closes [shareId]'s link; the next ack tells. */
    fun leave(shareId: String) {
        watchers = maxOf(0, watchers - 1)
    }

    /** The relay restarts and forgets [shareId]. */
    fun forget(shareId: String) {
        frames.remove(shareId)
        awaiting -= shareId
        _events.tryEmit(RelayEvent.Gone(shareId))
    }
}
