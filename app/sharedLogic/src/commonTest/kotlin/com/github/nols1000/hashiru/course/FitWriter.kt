package com.github.nols1000.hashiru.course

import com.github.nols1000.hashiru.course.codec.FitParser
import kotlin.math.roundToInt

/** Writes small FIT files for tests: definition and data messages, with a valid header and CRC. */
class FitWriter(private val bigEndian: Boolean = false) {
    private val records = mutableListOf<Byte>()

    private fun bytes(vararg values: Int) = values.forEach { records += it.toByte() }

    private fun int(value: Int, size: Int) {
        val order = if (bigEndian) (size - 1 downTo 0) else (0 until size)
        for (k in order) records += (value shr (8 * k)).toByte()
    }

    /** Defines local type [local] as the course message (31) with a 16-byte name. */
    fun defineCourse(local: Int = 0) {
        bytes(0x40 or local, 0, if (bigEndian) 1 else 0)
        int(31, 2)
        bytes(1, 5, 16, 7)
    }

    fun course(name: String, local: Int = 0) {
        bytes(local)
        val raw = name.encodeToByteArray().copyOf(16)
        raw.forEach { records += it }
    }

    /** Defines local type [local] as a record (20) with a timestamp, position and altitude; with developer fields. */
    fun defineRecord(local: Int = 1, developerBytes: Int = 0) {
        bytes(0x40 or local or (if (developerBytes > 0) 0x20 else 0), 0, if (bigEndian) 1 else 0)
        int(20, 2)
        bytes(4, 253, 4, 0x86, 0, 4, 0x85, 1, 4, 0x85, 2, 2, 0x84)
        if (developerBytes > 0) bytes(1, 0, developerBytes, 0)
    }

    fun record(lat: Double, lon: Double, altitude: Double?, local: Int = 1, timestamp: Int = 1_000_000_000, developerBytes: Int = 0, compressed: Boolean = false) {
        if (compressed) bytes(0x80 or (local shl 5) or 3) else bytes(local)
        int(timestamp, 4)
        int((lat / FitParser.SEMICIRCLE_DEGREES).roundToInt(), 4)
        int((lon / FitParser.SEMICIRCLE_DEGREES).roundToInt(), 4)
        int(altitude?.let { ((it + 500) * 5).roundToInt() } ?: 0xFFFF, 2)
        repeat(developerBytes) { bytes(0) }
    }

    fun build(): ByteArray {
        val data = records.toByteArray()
        val header = ByteArray(14)
        header[0] = 14
        header[1] = 0x20
        header[2] = 0x08
        header[3] = 0x08
        for (k in 0 until 4) header[4 + k] = (data.size shr (8 * k)).toByte()
        ".FIT".encodeToByteArray().copyInto(header, 8)
        val body = header + data
        val crc = FitParser.crc(body, 0, body.size)
        return body + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
    }
}
