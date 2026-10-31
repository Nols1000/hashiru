package com.github.nols1000.hashiru.share

import com.github.nols1000.hashiru.share.port.RelayOutcome
import com.github.nols1000.hashiru.share.port.RelayRequest
import com.github.nols1000.hashiru.share.port.relayOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayOutcomeTest {
    private val token = ByteArray(32)
    private val open = RelayRequest.Open("s", token, ByteArray(32))
    private val publish = RelayRequest.Publish("s", token, seq = 1, frame = ByteArray(1), snapshot = false)
    private val await = RelayRequest.AwaitViewers("s", token)
    private val close = RelayRequest.Close("s", token)
    private val all = listOf(open, publish, await, close)

    @Test
    fun successIsDone() {
        for (request in all) for (status in listOf(200, 204, 299)) {
            assertEquals(RelayOutcome.Done, relayOutcome(request, status))
        }
    }

    @Test
    fun anUnavailableRelayIsRetried() {
        // A redeploy answers 502 or 503 through the proxy for a few seconds; a full relay answers 503.
        for (request in all) for (status in listOf(408, 429, 500, 502, 503, 504, 599)) {
            assertEquals(RelayOutcome.Retry, relayOutcome(request, status), "$status")
        }
    }

    @Test
    fun aForgottenShareIsGone() {
        for (request in listOf(publish, await, close)) for (status in listOf(401, 403, 404)) {
            assertEquals(RelayOutcome.Gone, relayOutcome(request, status))
        }
    }

    @Test
    fun openingAShareTheRelayTurnsAwayIsRefused() {
        for (status in listOf(401, 403, 404)) assertEquals(RelayOutcome.Refused, relayOutcome(open, status))
    }

    @Test
    fun otherAnswersAreRefused() {
        for (request in all) for (status in listOf(100, 302, 400, 409, 413, 422)) {
            assertEquals(RelayOutcome.Refused, relayOutcome(request, status), "$status")
        }
    }
}
