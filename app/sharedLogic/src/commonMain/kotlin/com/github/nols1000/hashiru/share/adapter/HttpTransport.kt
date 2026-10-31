package com.github.nols1000.hashiru.share.adapter

/** One HTTP exchange with the relay, made the platform's way, for [HttpShareRelay]. */
fun interface HttpTransport {
    /**
     * Sends [body], if any, to [url] with [method], and `Authorization: Bearer` [bearerToken] where given. Returns
     * whatever status the relay answered with; throws when no answer comes, as without a network. Cancelling the
     * caller abandons the exchange.
     */
    suspend fun exchange(method: String, url: String, body: ByteArray?, bearerToken: String?): HttpResponse
}

/** What the relay answered. */
class HttpResponse(val status: Int, val body: ByteArray)
