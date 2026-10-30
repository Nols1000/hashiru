package com.github.nols1000.hashiru.treadmill

import com.github.nols1000.hashiru.treadmill.codec.WalkingPadPacketCodec
import com.github.nols1000.hashiru.treadmill.domain.BeltState
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WalkingPadPacketCodecTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun encodesPing() {
        assertContentEquals(bytes(0xF7, 0xA2, 0x00, 0x00, 0xA2, 0xFD), WalkingPadPacketCodec.encodePing())
    }

    @Test
    fun encodesSwitchToManualMode() {
        assertContentEquals(
            bytes(0xF7, 0xA2, 0x02, 0x01, 0xA5, 0xFD),
            WalkingPadPacketCodec.encodeSwitchMode(WalkingPadPacketCodec.MODE_MANUAL),
        )
    }

    @Test
    fun encodesStartBelt() {
        assertContentEquals(bytes(0xF7, 0xA2, 0x04, 0x01, 0xA7, 0xFD), WalkingPadPacketCodec.encodeStartBelt())
    }

    @Test
    fun encodesStopAsSpeedZero() {
        assertContentEquals(bytes(0xF7, 0xA2, 0x01, 0x00, 0xA3, 0xFD), WalkingPadPacketCodec.encodeSetSpeed(0))
    }

    @Test
    fun startCommandSwitchesToManualBeforeStartingBelt() {
        val frames = WalkingPadPacketCodec.encode(TreadmillCommand.StartBelt)
        assertEquals(2, frames.size)
        assertContentEquals(WalkingPadPacketCodec.encodeSwitchMode(WalkingPadPacketCodec.MODE_MANUAL), frames[0])
        assertContentEquals(WalkingPadPacketCodec.encodeStartBelt(), frames[1])
    }

    @Test
    fun setSpeedCommandEncodesTenths() {
        val frames = WalkingPadPacketCodec.encode(TreadmillCommand.SetSpeed(35))
        assertEquals(1, frames.size)
        assertContentEquals(bytes(0xF7, 0xA2, 0x01, 0x23, 0xC6, 0xFD), frames[0])
        assertEquals(35, WalkingPadPacketCodec.decodeSetSpeed(frames[0]))
        assertNull(WalkingPadPacketCodec.decodeSetSpeed(WalkingPadPacketCodec.encodePing()))
    }

    @Test
    fun setSpeedRejectsZero() {
        assertFailsWith<IllegalArgumentException> { WalkingPadPacketCodec.encode(TreadmillCommand.SetSpeed(0)) }
    }

    @Test
    fun encodedStatusRoundTripsSessionMetrics() {
        val packet = WalkingPadPacketCodec.encodeStatus(1, 35, 1, timeSeconds = 70_000, distanceMeters = 12_340, steps = 17_000)
        val status = assertNotNull(WalkingPadPacketCodec.decodeStatus(packet))
        assertEquals(70_000, status.timeSeconds)
        assertEquals(12_340, status.distanceMeters)
        assertEquals(17_000, status.steps)
    }

    @Test
    fun checksumWrapsAt256() {
        // 0xA2 + 0x01 + 0x78 = 0x11B -> 0x1B
        assertEquals(0x1B.toByte(), WalkingPadPacketCodec.checksum(bytes(0xF7, 0xA2, 0x01, 0x78, 0x00, 0xFD)))
        assertEquals(0x1B.toByte(), WalkingPadPacketCodec.encodeSetSpeed(0x78)[4])
    }

    @Test
    fun decodesStatusResponse() {
        val packet = bytes(
            0xF8, 0xA2,
            0x01, // belt running
            0x23, // 3.5 km/h
            0x01, // manual
            0x00, 0x01, 0x2C, // 300 s
            0x00, 0x00, 0x19, // 25 * 10 m
            0x00, 0x01, 0xF4, // 500 steps
            0x23, 0x00, 0x00, 0x00, 0x00, 0xFD,
        )
        val status = assertNotNull(WalkingPadPacketCodec.decodeStatus(packet))
        assertEquals(BeltState.Running, status.beltState)
        assertEquals(3.5, status.speedKmh)
        assertEquals(WalkingPadPacketCodec.MODE_MANUAL, status.mode)
        assertEquals(300, status.timeSeconds)
        assertEquals(250, status.distanceMeters)
        assertEquals(500, status.steps)
    }

    @Test
    fun keepsUnknownBeltStateRaw() {
        val status = assertNotNull(WalkingPadPacketCodec.decodeStatus(WalkingPadPacketCodec.encodeStatus(5, 0, 2)))
        assertEquals(BeltState.Other(5), status.beltState)
    }

    @Test
    fun rejectsWrongHeaderOrShortPacket() {
        assertNull(WalkingPadPacketCodec.decodeStatus(WalkingPadPacketCodec.encodePing()))
        val lastRecord = WalkingPadPacketCodec.encodeStatus(1, 10, 1).also { it[1] = 0xA7.toByte() }
        assertNull(WalkingPadPacketCodec.decodeStatus(lastRecord))
        assertNull(WalkingPadPacketCodec.decodeStatus(ByteArray(0)))
    }
}
