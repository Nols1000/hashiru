package com.github.nols1000.hashiru.share

import com.github.nols1000.hashiru.share.port.ShareCipherPort

/**
 * Deterministic stand-in for the platform's cryptography, for tests of what is sealed rather than how: "sealing"
 * leaves the plaintext readable and appends a blank tag, and signatures are blank. The real primitives are tested
 * against RFC 9605's vectors on each platform.
 */
class FakeShareCipher : ShareCipherPort {
    private var counter = 0
    val secrets = mutableMapOf<String, ByteArray>()
    val signingKeys = mutableMapOf<String, ByteArray>()

    override fun randomBytes(count: Int): ByteArray {
        counter++
        return ByteArray(count) { (counter * 31 + it).toByte() }
    }

    override fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        var h = 17
        for (b in key + data) h = h * 31 + b
        return ByteArray(64) { (h ushr (it % 4 * 8)).toByte() }
    }

    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray) = plaintext + ByteArray(TAG_BYTES)

    override fun createSigningKey(alias: String): ByteArray = ByteArray(65) { 4 }.also { signingKeys[alias] = it }

    override fun sign(alias: String, data: ByteArray): ByteArray? = if (alias in signingKeys) ByteArray(SIGNATURE_BYTES) else null

    override fun putSecret(alias: String, value: ByteArray?) {
        if (value == null) {
            secrets.remove(alias)
            signingKeys.remove(alias)
        } else {
            secrets[alias] = value
        }
    }

    override fun secret(alias: String): ByteArray? = secrets[alias]

    companion object {
        const val TAG_BYTES = 16
        const val SIGNATURE_BYTES = 64

        /** The plaintext of a frame this cipher sealed and signed: past the SFrame header, before tag and signature. */
        fun plaintext(frame: ByteArray): ByteArray {
            val config = frame[0].toInt() and 0xFF
            var header = 1
            if (config and 0x80 != 0) header += ((config shr 4) and 7) + 1
            if (config and 0x08 != 0) header += (config and 7) + 1
            return frame.copyOfRange(header, frame.size - TAG_BYTES - SIGNATURE_BYTES)
        }
    }
}
