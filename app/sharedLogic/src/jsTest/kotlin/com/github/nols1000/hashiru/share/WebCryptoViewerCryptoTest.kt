package com.github.nols1000.hashiru.share

import com.github.nols1000.hashiru.share.adapter.WebCryptoViewerCrypto
import com.github.nols1000.hashiru.share.codec.SFrameOpener
import com.github.nols1000.hashiru.share.port.ShareStreamPort
import com.github.nols1000.hashiru.share.port.StreamEvent
import com.github.nols1000.hashiru.share.viewer.ShareViewer
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The viewer's WebCrypto adapter, run on Node, which has the same `crypto.subtle` as browsers. */
class WebCryptoViewerCryptoTest {
    private val crypto = WebCryptoViewerCrypto()

    /** RFC 9605 appendix C.3, cipher suite 0x0005 (AES_256_GCM_SHA512_128). */
    @Test
    fun openingMatchesTheRfcVector() = runTest {
        val opener = SFrameOpener(crypto, "000102030405060708090a0b0c0d0e0f".unhex())
        val ct = "990123456794f509d36e9beacb0e261d99c7d1e972f1fed787d4049f17ca21353c1cc24d56ceabced279".unhex()
        val metadata = "4945544620534672616d65205747".unhex()

        assertEquals("64726166742d696574662d736672616d652d656e63", opener.open(ct, metadata)?.hex())
        ct[ct.size - 1] = (ct.last() + 1).toByte()
        assertNull(opener.open(ct, metadata))
    }

    /**
     * A frame sealed and signed on the JVM as the phone does (`javax.crypto`, with SHA256withECDSA in P1363 form),
     * opened as the web viewer does: the two sides agree on SFrame, the signature, the link and the message format.
     */
    @Test
    fun theViewerOpensWhatThePhoneSeals() = runTest {
        val viewer = ShareViewer(PHONE_FRAGMENT, crypto, OneConnection()) { 1_700_000_100_000 }
        viewer.run()

        val state = viewer.state.value
        assertEquals(true, state.ended)
        assertEquals(WorkoutKind.OutdoorWalk, state.kind)
        assertEquals(1_234, state.latest?.totals?.meters)
        assertEquals(118, state.latest?.heartRateBpm)
        assertEquals(listOf(52.5145, 52.51461), state.route.map { it.latitude })
    }

    @Test
    fun aFrameFromAnotherShareIsDropped() = runTest {
        // The same keys under another share id: the signature and the frame's metadata no longer match.
        val viewer = ShareViewer(PHONE_FRAGMENT.replace("hashiru-vector-share-1", "hashiru-vector-share-2"), crypto, OneConnection()) { 0 }
        viewer.run()

        assertEquals(true, viewer.state.value.ended)
        assertNull(viewer.state.value.latest)
    }

    /** Plays the phone's frame once; asked again, it no longer knows the share, which ends [ShareViewer.run]. */
    private class OneConnection : ShareStreamPort {
        private var challenges = 0
        override suspend fun challenge(shareId: String) = "nonce".takeIf { challenges++ == 0 }
        override fun stream(shareId: String, nonce: String, proof: ByteArray): Flow<StreamEvent> =
            flowOf(StreamEvent.Frame(300, snapshot = true, bytes = PHONE_FRAME.unhex()), StreamEvent.Ended)
    }

    private companion object {
        const val PHONE_FRAGMENT =
            "v1.hashiru-vector-share-1.nN9HazFzHKj3KBSImXXlSNhdbzfaQHoeb_wYYlvQf9w." +
                "BF_VYiwYQXuOLymqUClB5jJzPVJuOtpUbHp5vnXgNX6EBzxfg8FNAz6-iBsCdyBvDp20vyjPb8VZPX-vbNi3vJ4"
        const val PHONE_FRAME =
            "b9499602d2012cc58f96483b15aed2e7a972cf796b7cf4e5af317d429a807930d62c9a6f48ffefae414d50726232c958e1c7e772bd4705" +
                "72b5f00aabe9ee020a75cf2a895d9f55263d3662a298df4c8de546140743bd5af75397feb16d485e7f1faf9c758df6d1176b47dc2e20" +
                "80eaddd4c5590c4acafdab36d9ffe3afc8d56ffaa170d0320ddcc46211805507b4ca2f7c7f8072add8c961f19e77b4c61cf96494bf90" +
                "e7eb65ccfb96d16ac8565463d4d347662f229abfe20cc022bb385356ed2de5f43450286624268c85c98daf39b6a04e0b28cdc51e9aa3" +
                "3fd0631e3d0c2ed7a4e36277513d1c01b03a6908cea8544682b620e30925e4ff4798758ea03c71a8cc8a49e71f23505845bb4979f80" +
                "ca71665aa9aff325d15c4aa808709a8d4fd0624b9f42ef850fb94226c7e514c9acddf6b3f528461d4991afb9a89031b49ca149677e79" +
                "8ca806ebe2230bfd2eea496b5a9845c6fe31c"
    }
}
