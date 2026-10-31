package com.github.nols1000.hashiru.share.port

/** What an HTTP relay adapter makes of the status the relay answered a request with. */
internal enum class RelayOutcome {
    /** The relay took the request. */
    Done,

    /** The relay does not know the share, or not this token for it; see [RelayEvent.Gone]. */
    Gone,

    /** The relay, or the proxy in front of it, is restarting or busy for now: try again, as when offline. */
    Retry,

    /** The relay has no room for another share until some end: try again, as with [Retry]; see [RelayEvent.Busy]. */
    Full,

    /** The relay will not take the request however often it is sent; see [RelayEvent.Refused]. */
    Refused,
}

/**
 * Classifies [status] and [body] for [request]. Opening sends no token, so a relay that turns it away for a share it
 * knows under another token refuses the share rather than having forgotten it. A full relay answers 503 with the
 * body `full`; the 503 of a proxy in front of a restarting relay has a page of its own, or no body.
 */
internal fun relayOutcome(request: RelayRequest, status: Int, body: ByteArray = ByteArray(0)): RelayOutcome = when (status) {
    in 200..299 -> RelayOutcome.Done
    503 if body.size < 16 && body.decodeToString().trim() == FULL_BODY -> RelayOutcome.Full
    408, 429, in 500..599 -> RelayOutcome.Retry
    401, 403, 404 -> if (request is RelayRequest.Open) RelayOutcome.Refused else RelayOutcome.Gone
    else -> RelayOutcome.Refused
}

private const val FULL_BODY = "full"
