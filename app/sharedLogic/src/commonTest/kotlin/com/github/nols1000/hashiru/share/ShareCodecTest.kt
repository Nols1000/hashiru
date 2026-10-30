package com.github.nols1000.hashiru.share

import com.github.nols1000.hashiru.course.domain.CoursePoint
import com.github.nols1000.hashiru.share.codec.Base64Url
import com.github.nols1000.hashiru.share.codec.SFrame
import com.github.nols1000.hashiru.share.codec.ShareMessageCodec
import com.github.nols1000.hashiru.share.domain.ShareMessage
import com.github.nols1000.hashiru.share.domain.ShareNavigation
import com.github.nols1000.hashiru.share.domain.SharePhase
import com.github.nols1000.hashiru.share.domain.ShareState
import com.github.nols1000.hashiru.share.domain.SharedCourse
import com.github.nols1000.hashiru.workout.domain.PadCounters
import com.github.nols1000.hashiru.workout.domain.RoutePoint
import com.github.nols1000.hashiru.workout.domain.WorkoutKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareCodecTest {
    private val state = ShareState(
        atMs = 1_700_000_100_000,
        phase = SharePhase.Active,
        totals = PadCounters(600, 1_234, 1_500),
        heartRateBpm = 118,
        batteryPercent = 0,
        lastMovedMs = 1_700_000_040_000,
        navigation = ShareNavigation(800, 300, 4, onCourse = true, finished = false, joined = true),
    )
    private val route = listOf(
        RoutePoint(1_700_000_000_000, 52.520008, 13.404954),
        RoutePoint(1_700_000_001_000, 52.520051, 13.404899),
        RoutePoint(1_700_000_000_500, -33.8688197, 151.2092955),
    )

    @Test
    fun snapshotsRoundTrip() {
        val course = SharedCourse("Park ⛲", 1_100, listOf(CoursePoint(52.52, 13.40), CoursePoint(52.53, 13.41)))
        val message = ShareMessage.Snapshot(WorkoutKind.OutdoorRun, course, state, route)

        assertEquals(message, ShareMessageCodec.decode(ShareMessageCodec.encode(message)))
    }

    @Test
    fun updatesRoundTripWithoutOptionalValues() {
        val bare = ShareState(1_700_000_100_000, SharePhase.Paused, PadCounters.Zero)
        val message = ShareMessage.Update(bare, emptyList())

        assertEquals(message, ShareMessageCodec.decode(ShareMessageCodec.encode(message)))
    }

    @Test
    fun messagesArePaddedToBuckets() {
        val small = ShareMessageCodec.encode(ShareMessage.Update(state, emptyList()))
        val long = ShareMessageCodec.encode(ShareMessage.Update(state, List(100) { RoutePoint(it * 1_000L, it * 1e-4, 0.0) }))

        assertEquals(ShareMessageCodec.PAD_BYTES, small.size)
        assertEquals(0, long.size % ShareMessageCodec.PAD_BYTES)
    }

    @Test
    fun aNewerVersionDoesNotDecode() {
        val bytes = ShareMessageCodec.encode(ShareMessage.Update(state, route))
        bytes[0] = 2

        assertNull(ShareMessageCodec.decode(bytes))
        assertNull(ShareMessageCodec.decode(ByteArray(0)))
    }

    /** RFC 9605 appendix C.1. */
    @Test
    fun sframeHeadersMatchTheRfc() {
        val vectors = listOf(
            Triple(0L, 0L, "00"),
            Triple(0L, 1L, "01"),
            Triple(0L, 0xffL, "08ff"),
            Triple(0x123L, 0x4567L, "99012345 67".replace(" ", "")),
            Triple(0x00ffffffffffffffL, 0x0000000000000100L, "e9ffffffffffffff0100"),
            Triple(0x0100000000000000L, 0x00000000000000ffL, "f80100000000000000ff"),
            Triple(-1L, -1L, "ffffffffffffffffffffffffffffffffff"),
        )
        for ((kid, ctr, header) in vectors) assertEquals(header, SFrame.header(kid, ctr).hex(), "kid $kid ctr $ctr")
    }

    /** RFC 9605 appendix C.3, cipher suite 0x0005. */
    @Test
    fun sframeNoncesMatchTheRfc() {
        assertEquals("84991c167b8cd23c9370cba0", SFrame.nonce("84991c167b8cd23c93708ec7".unhex(), 0x4567).hex())
    }

    @Test
    fun base64UrlRoundTrips() {
        for (size in 0..34) {
            val bytes = ByteArray(size) { (it * 37 - 3).toByte() }
            assertContentEquals(bytes, Base64Url.decode(Base64Url.encode(bytes)))
        }
        assertEquals("-_8", Base64Url.encode(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
        assertNull(Base64Url.decode("a+b/"))
    }
}

fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

fun String.unhex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
