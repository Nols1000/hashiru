package com.github.nols1000.hashiru.share.codec

import com.github.nols1000.hashiru.share.port.ShareCipherPort
import com.github.nols1000.hashiru.share.port.ViewerCryptoPort

/** HKDF (RFC 5869) over HMAC-SHA-512. */
internal class Hkdf(private val cipher: ShareCipherPort) {
    /** An empty salt stands for a block of zeros, as the RFC has it; some platforms refuse empty HMAC keys. */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        cipher.hmacSha512(if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * HASH_BYTES)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            block = cipher.hmacSha512(prk, block + info + byteArrayOf(counter.toByte()))
            block.copyInto(out, filled, 0, minOf(block.size, length - filled))
            filled += block.size
            counter++
        }
        return out
    }

    companion object {
        const val HASH_BYTES = 64
    }
}

/**
 * SFrame (RFC 9605) with cipher suite AES_256_GCM_SHA512_128: each message is sealed under the key and salt
 * derived from the share's base key for a key id, with its counter as nonce. A counter must never repeat for one
 * key. The phone seals with [SFrameSealer], the web viewer opens with [SFrameOpener].
 */
object SFrame {
    const val CIPHER_SUITE = 0x0005
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12

    /** The key id, counter and header length of a frame. */
    class Header(val kid: Long, val ctr: Long, val length: Int)

    /** `X|KKK|Y|CCC`, then the key id and counter big-endian in as few bytes as they need, unless under 8. */
    fun header(kid: Long, ctr: Long): ByteArray {
        val kidBytes = minimal(kid)
        val ctrBytes = minimal(ctr)
        val k = if (kidBytes != null) 0x80 or ((minimalLength(kid) - 1) shl 4) else (kid.toInt() shl 4)
        val c = if (ctrBytes != null) 0x08 or (minimalLength(ctr) - 1) else ctr.toInt()
        return byteArrayOf((k or c).toByte()) + (kidBytes ?: ByteArray(0)) + (ctrBytes ?: ByteArray(0))
    }

    /** Null for a frame too short for its header. */
    fun parseHeader(frame: ByteArray): Header? {
        if (frame.isEmpty()) return null
        val config = frame[0].toInt() and 0xFF
        var at = 1
        var kid = ((config shr 4) and 7).toLong()
        var ctr = (config and 7).toLong()
        if (config and 0x80 != 0) {
            val length = ((config shr 4) and 7) + 1
            if (at + length > frame.size) return null
            kid = readBigEndian(frame, at, length)
            at += length
        }
        if (config and 0x08 != 0) {
            val length = (config and 7) + 1
            if (at + length > frame.size) return null
            ctr = readBigEndian(frame, at, length)
            at += length
        }
        return Header(kid, ctr, at)
    }

    fun nonce(salt: ByteArray, ctr: Long): ByteArray {
        val nonce = salt.copyOf()
        for (i in 0 until 8) {
            val index = nonce.size - 1 - i
            nonce[index] = (nonce[index].toInt() xor ((ctr ushr (8 * i)).toInt() and 0xFF)).toByte()
        }
        return nonce
    }

    internal fun keyLabel(kid: Long) = label("SFrame 1.0 Secret key ", kid)

    internal fun saltLabel(kid: Long) = label("SFrame 1.0 Secret salt ", kid)

    /** The label with the key id as 8 bytes and the cipher suite as 2, big-endian. */
    private fun label(prefix: String, kid: Long): ByteArray =
        prefix.encodeToByteArray() + bigEndian(kid, 8) + bigEndian(CIPHER_SUITE.toLong(), 2)

    /** Null when [value] fits in the 3 bits of the config byte. */
    private fun minimal(value: Long): ByteArray? =
        if (value in 0..7) null else bigEndian(value, minimalLength(value))

    private fun minimalLength(value: Long): Int {
        var length = 1
        while (length < 8 && (value ushr (8 * length)) != 0L) length++
        return length
    }

    private fun bigEndian(value: Long, length: Int) = ByteArray(length) { i -> (value ushr (8 * (length - 1 - i))).toByte() }

    private fun readBigEndian(bytes: ByteArray, from: Int, length: Int): Long {
        var value = 0L
        for (i in from until from + length) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        return value
    }
}

/** Seals a share's frames under key id [kid], deriving its key and salt once. */
internal class SFrameSealer(private val cipher: ShareCipherPort, baseKey: ByteArray, private val kid: Long) {
    private val key: ByteArray
    private val salt: ByteArray

    init {
        val hkdf = Hkdf(cipher)
        val secret = hkdf.extract(ByteArray(0), baseKey)
        key = hkdf.expand(secret, SFrame.keyLabel(kid), SFrame.KEY_BYTES)
        salt = hkdf.expand(secret, SFrame.saltLabel(kid), SFrame.NONCE_BYTES)
    }

    /** The SFrame: header, then ciphertext and tag; [metadata] is authenticated but not sent. */
    fun seal(ctr: Long, metadata: ByteArray, plaintext: ByteArray): ByteArray {
        val header = SFrame.header(kid, ctr)
        return header + cipher.aesGcmSeal(key, SFrame.nonce(salt, ctr), header + metadata, plaintext)
    }
}

/** Opens a share's frames under whichever key id they carry, deriving each key id's key and salt once. */
internal class SFrameOpener(private val crypto: ViewerCryptoPort, private val baseKey: ByteArray) {
    private class Keys(val key: ByteArray, val salt: ByteArray)

    private val keys = mutableMapOf<Long, Keys>()

    /** The plaintext, or null for a frame cut short, altered or sealed under another key. */
    suspend fun open(frame: ByteArray, metadata: ByteArray): ByteArray? {
        val header = SFrame.parseHeader(frame) ?: return null
        val keys = keys[header.kid] ?: Keys(
            crypto.hkdfSha512(baseKey, SFrame.keyLabel(header.kid), SFrame.KEY_BYTES),
            crypto.hkdfSha512(baseKey, SFrame.saltLabel(header.kid), SFrame.NONCE_BYTES),
        ).also { keys[header.kid] = it }
        val aad = frame.copyOfRange(0, header.length) + metadata
        return crypto.aesGcmOpen(keys.key, SFrame.nonce(keys.salt, header.ctr), aad, frame.copyOfRange(header.length, frame.size))
    }
}
