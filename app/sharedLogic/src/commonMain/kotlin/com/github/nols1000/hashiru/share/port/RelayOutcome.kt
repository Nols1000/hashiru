package com.github.nols1000.hashiru.share.port

/** What an HTTP relay adapter makes of the status the relay answered a request with. */
internal enum class RelayOutcome {
    /** The relay took the request. */
    Done,

    /** The relay does not know the share, or not this token for it; see [RelayEvent.Gone]. */
    Gone,

    /** The relay, or the proxy in front of it, is restarting, busy, or full for now: try again, as when offline. */
    Retry,

    /** The relay will not take the request however often it is sent; see [RelayEvent.Refused]. */
    Refused,
}

/**
 * Classifies [status] for [request]. Opening sends no token, so a relay that turns it away for a share it knows
 * under another token refuses the share rather than having forgotten it.
 */
internal fun relayOutcome(request: RelayRequest, status: Int): RelayOutcome = when (status) {
    in 200..299 -> RelayOutcome.Done
    408, 429, in 500..599 -> RelayOutcome.Retry
    401, 403, 404 -> if (request is RelayRequest.Open) RelayOutcome.Refused else RelayOutcome.Gone
    else -> RelayOutcome.Refused
}
