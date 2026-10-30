package com.github.nols1000.hashiru.course.codec

import com.github.nols1000.hashiru.course.domain.CoursePoint
import com.github.nols1000.hashiru.course.domain.ParsedCourse

/**
 * Reads the positions out of a FIT file (Garmin's Flexible and Interoperable Data Transfer), whether a course or a
 * recorded activity: the `record` messages' latitude, longitude and altitude, and the course's name. Everything
 * else is read past. Files chained one after another are read in turn.
 *
 * The format, in short: a header, then records that each start with a one-byte header. A definition record says
 * how the data records of one local type are laid out (global message number, byte order, fields); a data record
 * then holds the values of those fields. A compressed-timestamp header is a data record whose local type sits in
 * the header byte. A CRC-16 over header and records ends the file.
 */
object FitParser {
    sealed interface Result {
        data class Parsed(val course: ParsedCourse) : Result
        data object NotFit : Result
        data object Corrupt : Result
    }

    /** True when [bytes] start with a FIT header. */
    fun recognizes(bytes: ByteArray): Boolean =
        bytes.size >= 12 && (bytes[0].toInt() == 12 || bytes[0].toInt() == 14) && bytes.decodeToString(8, 12) == ".FIT"

    fun parse(bytes: ByteArray): Result {
        if (!recognizes(bytes)) return Result.NotFit
        val points = mutableListOf<CoursePoint>()
        var name: String? = null
        var offset = 0
        try {
            while (offset + 12 <= bytes.size && recognizesAt(bytes, offset)) {
                val headerSize = bytes[offset].toInt()
                val dataSize = uint32Le(bytes, offset + 4).toInt()
                val end = offset + headerSize + dataSize
                if (dataSize < 0 || end + 2 > bytes.size) return Result.Corrupt
                if (crc(bytes, offset, end) != uint16Le(bytes, end)) return Result.Corrupt
                val file = readRecords(bytes, offset + headerSize, end)
                points += file.points
                if (name == null) name = file.name
                offset = end + 2
            }
        } catch (_: IndexOutOfBoundsException) {
            return Result.Corrupt
        }
        return Result.Parsed(ParsedCourse(name, points))
    }

    private fun recognizesAt(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() == 12 || bytes[offset].toInt() == 14) && bytes.decodeToString(offset + 8, offset + 12) == ".FIT"

    private class Field(val number: Int, val size: Int)

    private class Definition(val global: Int, val bigEndian: Boolean, val fields: List<Field>, val developerBytes: Int)

    private fun readRecords(bytes: ByteArray, from: Int, to: Int): ParsedCourse {
        val definitions = arrayOfNulls<Definition>(16)
        val points = mutableListOf<CoursePoint>()
        var name: String? = null
        var i = from
        while (i < to) {
            val header = bytes[i].toInt() and 0xFF
            i++
            val compressed = header and 0x80 != 0
            if (!compressed && header and 0x40 != 0) {
                val developer = header and 0x20 != 0
                val bigEndian = bytes[i + 1].toInt() == 1
                val global = if (bigEndian) uint16Be(bytes, i + 2) else uint16Le(bytes, i + 2)
                val count = bytes[i + 4].toInt() and 0xFF
                i += 5
                val fields = List(count) { Field(bytes[i + it * 3].toInt() and 0xFF, bytes[i + it * 3 + 1].toInt() and 0xFF) }
                i += count * 3
                var developerBytes = 0
                if (developer) {
                    val devCount = bytes[i].toInt() and 0xFF
                    i++
                    for (d in 0 until devCount) developerBytes += bytes[i + d * 3 + 1].toInt() and 0xFF
                    i += devCount * 3
                }
                definitions[header and 0x0F] = Definition(global, bigEndian, fields, developerBytes)
                continue
            }
            val local = if (compressed) (header shr 5) and 0x03 else header and 0x0F
            val definition = definitions[local] ?: throw IndexOutOfBoundsException("Data before its definition")
            var lat: Int? = null
            var lon: Int? = null
            var altitude: Double? = null
            var enhancedAltitude: Double? = null
            for (field in definition.fields) {
                val at = i
                i += field.size
                if (i > to) throw IndexOutOfBoundsException("Record runs past the data")
                when (definition.global) {
                    RECORD -> when {
                        field.number == POSITION_LAT && field.size == 4 -> lat = sint32(bytes, at, definition.bigEndian).takeIf { it != INVALID_SINT32 }
                        field.number == POSITION_LONG && field.size == 4 -> lon = sint32(bytes, at, definition.bigEndian).takeIf { it != INVALID_SINT32 }
                        field.number == ALTITUDE && field.size == 2 -> {
                            val raw = if (definition.bigEndian) uint16Be(bytes, at) else uint16Le(bytes, at)
                            if (raw != 0xFFFF) altitude = raw / 5.0 - 500
                        }
                        field.number == ENHANCED_ALTITUDE && field.size == 4 -> {
                            val raw = sint32(bytes, at, definition.bigEndian).toLong() and 0xFFFFFFFFL
                            if (raw != 0xFFFFFFFFL) enhancedAltitude = raw / 5.0 - 500
                        }
                    }
                    COURSE -> if (field.number == COURSE_NAME && name == null) {
                        val raw = bytes.copyOfRange(at, at + field.size)
                        name = raw.decodeToString(0, raw.indexOf(0).let { if (it < 0) raw.size else it }).trim().ifEmpty { null }
                    }
                }
            }
            i += definition.developerBytes
            if (definition.global == RECORD && lat != null && lon != null) {
                points += CoursePoint(lat * SEMICIRCLE_DEGREES, lon * SEMICIRCLE_DEGREES, enhancedAltitude ?: altitude)
            }
        }
        return ParsedCourse(name, points)
    }

    /** FIT's CRC-16 over [from] until [to]. */
    fun crc(bytes: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            val b = bytes[i].toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[b and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(b shr 4) and 0xF]
        }
        return crc
    }

    private fun uint16Le(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun uint16Be(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun uint32Le(b: ByteArray, at: Int): Long = sint32(b, at, bigEndian = false).toLong() and 0xFFFFFFFFL

    private fun sint32(b: ByteArray, at: Int, bigEndian: Boolean): Int {
        var v = 0
        for (k in 0 until 4) {
            val byte = b[if (bigEndian) at + k else at + 3 - k].toInt() and 0xFF
            v = (v shl 8) or byte
        }
        return v
    }

    private const val RECORD = 20
    private const val COURSE = 31
    private const val POSITION_LAT = 0
    private const val POSITION_LONG = 1
    private const val ALTITUDE = 2
    private const val ENHANCED_ALTITUDE = 78
    private const val COURSE_NAME = 5
    private const val INVALID_SINT32 = 0x7FFFFFFF

    /** Degrees per semicircle: 2^31 semicircles make 180 degrees. */
    const val SEMICIRCLE_DEGREES = 180.0 / 2147483648.0

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )
}
