package com.github.nols1000.hashiru.share.adapter

import com.github.nols1000.hashiru.share.port.RelayEvent
import com.github.nols1000.hashiru.share.port.RelayRequest
import com.github.nols1000.hashiru.share.port.ShareRelayPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Relay kept in memory, answering at once. It reports [watchers] pages open on every share; set [reachable]
 * to false to have requests fail as without a network, and call [arrive] to have a viewer open the link.
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

    var reachable = true
        set(value) {
            if (field == value) return
            field = value
            _events.tryEmit(if (value) RelayEvent.Reachable else RelayEvent.Unreachable)
        }

    override fun handle(request: RelayRequest) {
        _requests += request
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
        _events.tryEmit(RelayEvent.Watchers(shareId, watchers))
    }

    /** The relay restarts and forgets [shareId]. */
    fun forget(shareId: String) {
        frames.remove(shareId)
        _events.tryEmit(RelayEvent.Gone(shareId))
    }
}
