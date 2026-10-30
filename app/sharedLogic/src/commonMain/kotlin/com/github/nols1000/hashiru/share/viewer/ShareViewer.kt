package com.github.nols1000.hashiru.share.viewer

import com.github.nols1000.hashiru.share.codec.SFrameOpener
import com.github.nols1000.hashiru.share.codec.ShareLink
import com.github.nols1000.hashiru.share.codec.ShareMessageCodec
import com.github.nols1000.hashiru.share.domain.ShareMessage
import com.github.nols1000.hashiru.share.domain.ShareState
import com.github.nols1000.hashiru.share.domain.SharedCourse
import com.github.nols1000.hashiru.share.engine.LiveShareEngine
import com.github.nols1000.hashiru.share.port.ShareStreamPort
import com.github.nols1000.hashiru.share.port.StreamEvent
import com.github.nols1000.hashiru.share.port.ViewerCryptoPort
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ViewerConnection {
    Connecting,
    Live,

    /** The connection dropped; trying again. */
    Reconnecting,

    /** The share has as many viewers as the relay allows; trying again later. */
    Full,

    /** The relay does not know the share: it ended a while ago, or the link is wrong. */
    NotFound,

    /** The link is incomplete, as when copied in part. */
    InvalidLink,
}

/** What a viewer knows of the share. [receivedAtMs] is when the last frame arrived, by this device's clock. */
data class ViewerState(
    val connection: ViewerConnection = ViewerConnection.Connecting,
    val kind: WorkoutKind? = null,
    val course: SharedCourse? = null,
    /** The route so far, without repeats. */
    val route: List<RoutePoint> = emptyList(),
    val latest: ShareState? = null,
    val receivedAtMs: Long? = null,
    val ended: Boolean = false,
)

/**
 * Follows a live share from its link's fragment: proves to the relay that it holds the link, then checks each
 * frame's signature, opens and decodes it. Updates may repeat positions the relay did not confirm to the phone
 * in time; they are kept once. A spent challenge cannot be reused, so each reconnect starts with a new one.
 */
class ShareViewer(
    fragment: String,
    private val crypto: ViewerCryptoPort,
    private val stream: ShareStreamPort,
    private val nowMs: () -> Long,
) {
    private val link = ShareLink.parse(fragment)
    private val opener = link?.let { SFrameOpener(crypto, it.baseKey) }
    private val metadata = link?.let { LiveShareEngine.metadata(it.shareId) }

    private val _state = MutableStateFlow(ViewerState(if (link == null) ViewerConnection.InvalidLink else ViewerConnection.Connecting))
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    /** Follows the share until cancelled, or until it turns out not to exist, or no longer to. */
    suspend fun run() {
        val link = link ?: return
        val presence = crypto.hkdfSha512(link.baseKey, LiveShareEngine.PRESENCE_LABEL, PRESENCE_BYTES)
        var backoff = MIN_BACKOFF_MS
        // How long the relay has not known a share this viewer followed: a restarted relay forgets every share
        // until the phones open theirs again.
        var forgottenMs: Long? = null
        while (true) {
            val wait = try {
                val nonce = stream.challenge(link.shareId)
                if (nonce == null) {
                    val forgotten = forgottenMs
                    if (_state.value.latest == null || _state.value.ended || (forgotten != null && forgotten >= REOPEN_GRACE_MS)) {
                        _state.value = _state.value.copy(connection = ViewerConnection.NotFound)
                        return
                    }
                    forgottenMs = (forgotten ?: 0) + backoff
                    backoff
                } else {
                    forgottenMs = null
                    val proof = crypto.hmacSha512(presence, nonce.encodeToByteArray())
                    var full = false
                    stream.stream(link.shareId, nonce, proof).collect { event ->
                        when (event) {
                            is StreamEvent.Frame -> if (receive(link, event.bytes)) backoff = MIN_BACKOFF_MS
                            StreamEvent.Ended -> _state.value = _state.value.copy(ended = true)
                            StreamEvent.Denied -> Unit
                            StreamEvent.Full -> full = true
                        }
                    }
                    if (full) {
                        _state.value = _state.value.copy(connection = ViewerConnection.Full)
                        FULL_RETRY_MS
                    } else {
                        backoff
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Not only exceptions: in browsers a failed fetch rejects with a JavaScript TypeError.
                backoff
            }
            if (_state.value.connection != ViewerConnection.Full) _state.value = _state.value.copy(connection = ViewerConnection.Reconnecting)
            delay(wait)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** True for a genuine frame; forged, altered or unreadable ones are dropped. */
    private suspend fun receive(link: ShareLink, signed: ByteArray): Boolean {
        if (signed.size <= SIGNATURE_BYTES) return false
        val frame = signed.copyOfRange(0, signed.size - SIGNATURE_BYTES)
        val signature = signed.copyOfRange(signed.size - SIGNATURE_BYTES, signed.size)
        if (!crypto.verifyP256(link.signingKey, signature, metadata!! + frame)) return false
        val plaintext = opener!!.open(frame, metadata) ?: return false
        val message = ShareMessageCodec.decode(plaintext) ?: return false
        _state.value = apply(_state.value.copy(connection = ViewerConnection.Live, receivedAtMs = nowMs()), message)
        return true
    }

    companion object {
        private const val PRESENCE_BYTES = 32
        private const val SIGNATURE_BYTES = 64
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val FULL_RETRY_MS = 30_000L

        /** How long a viewer waits for the phone to open its share again on a relay that forgot it. */
        private const val REOPEN_GRACE_MS = 10 * 60_000L

        internal fun apply(state: ViewerState, message: ShareMessage): ViewerState {
            val latest = state.latest?.takeIf { it.atMs > message.state.atMs } ?: message.state
            return when (message) {
                is ShareMessage.Snapshot -> state.copy(kind = message.kind, course = message.course, route = message.route, latest = latest)
                is ShareMessage.Update -> {
                    val last = state.route.lastOrNull()?.atMs ?: Long.MIN_VALUE
                    state.copy(route = state.route + message.route.filter { it.atMs > last }, latest = latest)
                }
            }
        }
    }
}
