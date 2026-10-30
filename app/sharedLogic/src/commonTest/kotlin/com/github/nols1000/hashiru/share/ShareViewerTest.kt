package com.github.nols1000.hashiru.share

import com.github.nols1000.hashiru.share.codec.SFrameSealer
import com.github.nols1000.hashiru.share.codec.ShareLink
import com.github.nols1000.hashiru.share.codec.ShareMessageCodec
import com.github.nols1000.hashiru.share.domain.ShareMessage
import com.github.nols1000.hashiru.share.domain.ShareNavigation
import com.github.nols1000.hashiru.share.domain.SharePhase
import com.github.nols1000.hashiru.share.domain.ShareState
import com.github.nols1000.hashiru.share.engine.LiveShareEngine
import com.github.nols1000.hashiru.share.port.ShareStreamPort
import com.github.nols1000.hashiru.share.port.StreamEvent
import com.github.nols1000.hashiru.share.port.ViewerCryptoPort
import com.github.nols1000.hashiru.share.viewer.ShareViewer
import com.github.nols1000.hashiru.share.viewer.ViewerConnection
import com.github.nols1000.hashiru.share.viewer.ViewerNote
import com.github.nols1000.hashiru.share.viewer.ViewerState
import com.github.nols1000.hashiru.share.viewer.viewerUi
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewerTest {
    private val id = "AAAAAAAAAAAAAAAAAAAAAA"
    private val link = ShareLink(id, ByteArray(32) { 7 }, ByteArray(65) { 4 })
    private val cipher = FakeShareCipher()
    private val sealer = SFrameSealer(cipher, link.baseKey, kid = 9)
    private val epoch = 1_700_000_000_000L

    /** Reverses [FakeShareCipher]: frames are readable, signatures blank unless forged. */
    private val crypto = object : ViewerCryptoPort {
        val proofs = mutableListOf<ByteArray>()
        override suspend fun hkdfSha512(ikm: ByteArray, info: ByteArray, length: Int) = ByteArray(length) { (info.size + it).toByte() }
        override suspend fun hmacSha512(key: ByteArray, data: ByteArray) = (key + data).also { proofs += it }
        override suspend fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray) =
            ciphertext.copyOfRange(0, ciphertext.size - FakeShareCipher.TAG_BYTES)
        override suspend fun verifyP256(publicKey: ByteArray, signature: ByteArray, data: ByteArray) = signature.all { it == 0.toByte() }
    }

    /** Each connection plays the next list of events, then drops. */
    private inner class Relay(vararg sessions: List<StreamEvent>) : ShareStreamPort {
        val sessions = sessions.toMutableList()
        val nonces = mutableListOf<String>()
        var known = true

        override suspend fun challenge(shareId: String): String? = if (known) "nonce-${nonces.size}".also { nonces += it } else null

        override fun stream(shareId: String, nonce: String, proof: ByteArray): Flow<StreamEvent> = flow {
            sessions.removeFirstOrNull()?.forEach { emit(it) }
        }
    }

    private var seq = 0L

    private fun frame(message: ShareMessage, forged: Boolean = false): StreamEvent.Frame {
        val metadata = LiveShareEngine.metadata(id)
        val sealed = sealer.seal(seq, metadata, ShareMessageCodec.encode(message))
        return StreamEvent.Frame(seq++, message is ShareMessage.Snapshot, sealed + ByteArray(64) { if (forged) 1 else 0 })
    }

    private fun state(atMs: Long, phase: SharePhase = SharePhase.Active, meters: Int = 100) =
        ShareState(atMs, phase, PadCounters(60, meters, 80))

    private fun point(i: Int) = RoutePoint(epoch + i * 1_000L, 52.0 + i * 1e-4, 13.0)

    @Test
    fun snapshotsAndUpdatesBuildTheRouteWithoutRepeats() = runTest {
        val relay = Relay(
            listOf(
                frame(ShareMessage.Snapshot(WorkoutKind.OutdoorWalk, null, state(epoch + 2_000), listOf(point(0), point(1), point(2)))),
                // Positions the phone sent again, as the relay's answer did not reach it.
                frame(ShareMessage.Update(state(epoch + 4_000), listOf(point(2), point(3), point(4)))),
            ),
        )
        val viewer = ShareViewer(link.fragment(), crypto, relay) { epoch + 5_000 }
        val job = launch { viewer.run() }
        runCurrent()

        val state = viewer.state.value
        assertEquals((0..4).map(::point), state.route)
        assertEquals(epoch + 4_000, state.latest?.atMs)
        assertEquals(WorkoutKind.OutdoorWalk, state.kind)
        job.cancel()
    }

    @Test
    fun forgedFramesAreDropped() = runTest {
        val relay = Relay(
            listOf(
                frame(ShareMessage.Snapshot(WorkoutKind.OutdoorWalk, null, state(epoch), listOf(point(0)))),
                frame(ShareMessage.Update(state(epoch + 1_000), listOf(point(1))), forged = true),
            ),
        )
        val viewer = ShareViewer(link.fragment(), crypto, relay) { epoch }
        val job = launch { viewer.run() }
        runCurrent()

        assertEquals(listOf(point(0)), viewer.state.value.route)
        job.cancel()
    }

    @Test
    fun aDroppedConnectionStartsOverWithANewChallenge() = runTest {
        val relay = Relay(
            listOf(StreamEvent.Denied),
            listOf(frame(ShareMessage.Snapshot(WorkoutKind.OutdoorRun, null, state(epoch), emptyList()))),
        )
        val viewer = ShareViewer(link.fragment(), crypto, relay) { epoch }
        val job = launch { viewer.run() }
        runCurrent()
        assertEquals(ViewerConnection.Reconnecting, viewer.state.value.connection)

        advanceTimeBy(2_001)
        assertEquals(listOf("nonce-0", "nonce-1"), relay.nonces)
        assertEquals(WorkoutKind.OutdoorRun, viewer.state.value.kind)
        job.cancel()
    }

    @Test
    fun anyFailureOfTheConnectionIsRetried() = runTest {
        var attempts = 0
        val relay = object : ShareStreamPort {
            override suspend fun challenge(shareId: String): String? {
                // Browsers reject a failed fetch with a JavaScript error, which is no Kotlin exception.
                if (attempts++ == 0) throw Error("network")
                return null
            }
            override fun stream(shareId: String, nonce: String, proof: ByteArray): Flow<StreamEvent> = flow {}
        }
        val viewer = ShareViewer(link.fragment(), crypto, relay) { epoch }
        viewer.run()

        assertEquals(2, attempts)
        assertEquals(ViewerConnection.NotFound, viewer.state.value.connection)
    }

    @Test
    fun aShareTheRelayDoesNotKnowIsNotFound() = runTest {
        val relay = Relay().apply { known = false }
        val viewer = ShareViewer(link.fragment(), crypto, relay) { epoch }
        viewer.run()

        assertEquals(ViewerConnection.NotFound, viewer.state.value.connection)
    }

    @Test
    fun aShareTheRelayForgotIsAwaitedUntilThePhoneOpensItAgain() = runTest {
        val relay = Relay(
            listOf(frame(ShareMessage.Snapshot(WorkoutKind.OutdoorWalk, null, state(epoch), listOf(point(0))))),
            listOf(frame(ShareMessage.Update(state(epoch + 1_000), listOf(point(1))))),
        )
        val viewer = ShareViewer(link.fragment(), crypto, relay) { epoch }
        val job = launch { viewer.run() }
        runCurrent()
        // The relay restarted: it does not know the share until the phone opens it again.
        relay.known = false
        advanceTimeBy(2_001)
        assertEquals(ViewerConnection.Reconnecting, viewer.state.value.connection)

        relay.known = true
        advanceTimeBy(4_001)
        assertEquals(listOf(point(0), point(1)), viewer.state.value.route)
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test
    fun aShareTheRelayKeepsForgettingIsNotFoundAfterAWhile() = runTest {
        val relay = Relay(listOf(frame(ShareMessage.Snapshot(WorkoutKind.OutdoorWalk, null, state(epoch), emptyList()))))
        val viewer = ShareViewer(link.fragment(), crypto, relay) { epoch }
        val job = launch { viewer.run() }
        runCurrent()
        relay.known = false
        advanceTimeBy(10 * 60_000L)
        assertEquals(ViewerConnection.Reconnecting, viewer.state.value.connection)

        advanceTimeBy(5 * 60_000L)
        assertEquals(ViewerConnection.NotFound, viewer.state.value.connection)
        assertTrue(job.isCompleted)
    }

    @Test
    fun anIncompleteLinkIsInvalid() = runTest {
        val viewer = ShareViewer(link.fragment().dropLast(3), crypto, Relay()) { epoch }
        viewer.run()

        assertEquals(ViewerConnection.InvalidLink, viewer.state.value.connection)
        assertNull(ShareLink.parse("v1.$id.short.key"))
        assertEquals(id, ShareLink.parse("#" + link.fragment())?.shareId)
    }

    @Test
    fun theViewerWarnsWhenTheWalkerGoesQuietOrStops() {
        val latest = ShareState(epoch, SharePhase.Active, PadCounters(600, 1_200, 0), lastMovedMs = epoch - 4 * 60_000,
            navigation = ShareNavigation(800, 400, 60, onCourse = false, finished = false, joined = true))
        val state = ViewerState(ViewerConnection.Live, WorkoutKind.OutdoorWalk, latest = latest, receivedAtMs = epoch)

        val now = viewerUi(state, epoch)
        assertEquals(listOf<ViewerNote>(ViewerNote.OffCourse(60)), now.notes)
        assertEquals(500, now.paceSecondsPerKm)

        val later = viewerUi(state, epoch + 4 * 60_000)
        assertEquals(listOf(ViewerNote.Silent(4), ViewerNote.NotMoving(8), ViewerNote.OffCourse(60)), later.notes)
        // Active time runs on between updates.
        assertEquals(600 + 240, later.seconds)

        val ended = viewerUi(state.copy(ended = true), epoch + 60 * 60_000)
        assertEquals(SharePhase.Ended, ended.phase)
        assertTrue(ended.notes.isEmpty())
        assertEquals(600, ended.seconds)
    }
}
