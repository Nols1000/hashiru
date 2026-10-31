package com.github.nols1000.hashiru.treadmill.codec

import com.github.nols1000.hashiru.treadmill.domain.BeltState
import com.github.nols1000.hashiru.treadmill.domain.TreadmillCommand

data class WalkingPadStatus(
    val beltState: BeltState,
    val speedKmh: Double,
    val mode: Int,
    val timeSeconds: Int,
    val distanceMeters: Int,
    val steps: Int,
)

/**
 * KingSmith WalkingPad wire format, following ph4-walkingpad (`ph4_walkingpad/pad.py`).
 *
 * Commands: `F7 A2 <op> <arg> <checksum> FD`. Status responses: `F8 A2 <state> <speed> <mode> ... <checksum> FD`.
 * The checksum is the sum of every byte between the header and the checksum itself, mod 256, in both directions.
 */
object WalkingPadPacketCodec {
    const val COMMAND_HEADER: Byte = 0xF7.toByte()
    const val RESPONSE_HEADER: Byte = 0xF8.toByte()
    const val FOOTER: Byte = 0xFD.toByte()
    const val STATUS_OPCODE: Byte = 0xA2.toByte()

    const val MODE_AUTO = 0
    const val MODE_MANUAL = 1
    const val MODE_STANDBY = 2

    private const val OP_PING = 0x00
    private const val OP_SET_SPEED = 0x01
    private const val OP_SWITCH_MODE = 0x02
    private const val OP_START_BELT = 0x04

    private const val MIN_STATUS_SIZE = 16

    fun encodePing(): ByteArray = command(OP_PING, 0)
    fun encodeSwitchMode(mode: Int): ByteArray = command(OP_SWITCH_MODE, mode)
    fun encodeStartBelt(): ByteArray = command(OP_START_BELT, 1)

    /** [tenthsKmh] is the speed in 0.1 km/h units, so 0 stops the belt. */
    fun encodeSetSpeed(tenthsKmh: Int): ByteArray = command(OP_SET_SPEED, tenthsKmh)

    /** Frames to write, in order, to execute [command]. */
    fun encode(command: TreadmillCommand): List<ByteArray> = when (command) {
        TreadmillCommand.StartBelt -> listOf(encodeSwitchMode(MODE_MANUAL), encodeStartBelt())
        TreadmillCommand.StopBelt -> listOf(encodeSetSpeed(0))
        TreadmillCommand.RequestStatusPing -> listOf(encodePing())
        is TreadmillCommand.SetSpeed -> {
            require(command.tenthsKmh > 0) { "Use StopBelt to stop the belt" }
            listOf(encodeSetSpeed(command.tenthsKmh))
        }
    }

    /** The speed argument of a set-speed command frame, or null for any other frame; used by the mock transport. */
    internal fun decodeSetSpeed(bytes: ByteArray): Int? =
        if (bytes.size == 6 && bytes[0] == COMMAND_HEADER && bytes.u8(2) == OP_SET_SPEED) bytes.u8(3) else null

    /** Null for anything but a whole status packet: a garbled one could look like a pad reset. */
    fun decodeStatus(bytes: ByteArray): WalkingPadStatus? {
        if (bytes.size < MIN_STATUS_SIZE || bytes[0] != RESPONSE_HEADER || bytes[1] != STATUS_OPCODE) return null
        if (bytes.last() != FOOTER || bytes[bytes.size - 2] != checksum(bytes)) return null
        return WalkingPadStatus(
            beltState = BeltState.fromCode(bytes.u8(2)),
            speedKmh = bytes.u8(3) / 10.0,
            mode = bytes.u8(4),
            timeSeconds = bytes.u24(5),
            distanceMeters = bytes.u24(8) * 10,
            steps = bytes.u24(11),
        )
    }

    /** Builds a 20-byte status packet; used by the mock transport. */
    internal fun encodeStatus(
        beltState: Int,
        speedTenthsKmh: Int,
        mode: Int,
        timeSeconds: Int = 0,
        distanceMeters: Int = 0,
        steps: Int = 0,
    ): ByteArray {
        val bytes = ByteArray(20)
        bytes[0] = RESPONSE_HEADER
        bytes[1] = STATUS_OPCODE
        bytes[2] = beltState.toByte()
        bytes[3] = speedTenthsKmh.toByte()
        bytes[4] = mode.toByte()
        bytes.putU24(5, timeSeconds)
        bytes.putU24(8, distanceMeters / 10)
        bytes.putU24(11, steps)
        bytes[19] = FOOTER
        bytes[18] = checksum(bytes)
        return bytes
    }

    fun checksum(frame: ByteArray): Byte =
        (1 until frame.size - 2).sumOf { frame.u8(it) }.rem(256).toByte()

    private fun command(opcode: Int, arg: Int): ByteArray {
        val frame = byteArrayOf(COMMAND_HEADER, STATUS_OPCODE, opcode.toByte(), arg.toByte(), 0, FOOTER)
        frame[frame.size - 2] = checksum(frame)
        return frame
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
    private fun ByteArray.u24(index: Int): Int = (u8(index) shl 16) or (u8(index + 1) shl 8) or u8(index + 2)
    private fun ByteArray.putU24(index: Int, value: Int) {
        this[index] = (value shr 16).toByte()
        this[index + 1] = (value shr 8).toByte()
        this[index + 2] = value.toByte()
    }
}
