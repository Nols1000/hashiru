package com.github.nols1000.hashiru.share.codec

/** Appends unsigned LEB128 varints, zigzag-encoded signed ones and raw bytes. */
internal class ByteWriter {
    private val out = ArrayList<Byte>(256)

    val size: Int get() = out.size

    fun byte(value: Int) {
        out += value.toByte()
    }

    fun bytes(value: ByteArray) {
        value.forEach { out += it }
    }

    fun varint(value: Long) {
        require(value >= 0) { "negative varint" }
        var v = value
        while (v >= 0x80) {
            out += ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        out += v.toByte()
    }

    fun varint(value: Int) = varint(value.toLong())

    fun zigzag(value: Long) = varint((value shl 1) xor (value shr 63))

    fun text(value: String) {
        val utf8 = value.encodeToByteArray()
        varint(utf8.size)
        bytes(utf8)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Reads what [ByteWriter] wrote; throws [IllegalArgumentException] past the end. */
internal class ByteReader(private val bytes: ByteArray) {
    var position = 0
        private set

    fun byte(): Int {
        require(position < bytes.size) { "truncated" }
        return bytes[position++].toInt() and 0xFF
    }

    fun bytes(count: Int): ByteArray {
        require(count >= 0 && position + count <= bytes.size) { "truncated" }
        return bytes.copyOfRange(position, position + count).also { position += count }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(shift < 64) { "varint too long" }
            val b = byte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun int(): Int = varint().also { require(it <= Int.MAX_VALUE) { "out of range" } }.toInt()

    fun zigzag(): Long {
        val v = varint()
        return (v ushr 1) xor -(v and 1)
    }

    fun text(): String = bytes(int()).decodeToString(throwOnInvalidSequence = true)
}

/** URL-safe base64 without padding, as links carry keys (RFC 4648 §5). */
internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String = buildString {
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            append(ALPHABET[b0 shr 2])
            append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            if (i + 1 < bytes.size) append(ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)])
            if (i + 2 < bytes.size) append(ALPHABET[b2 and 63])
            i += 3
        }
    }

    /** Null for anything but unpadded URL-safe base64. */
    fun decode(text: String): ByteArray? {
        if (text.length % 4 == 1) return null
        val out = ArrayList<Byte>(text.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in text) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out += (buffer shr bits).toByte()
            }
        }
        return out.toByteArray()
    }
}
