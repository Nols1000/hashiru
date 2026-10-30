package com.github.nols1000.hashiru.share

import com.github.nols1000.hashiru.share.adapter.JcaShareCrypto
import com.github.nols1000.hashiru.share.codec.SFrameSealer
import com.github.nols1000.hashiru.share.port.ShareCipherPort
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JcaShareCryptoTest {
    /** The Keystore-free half of the Android adapter. */
    private val cipher = object : ShareCipherPort {
        override fun randomBytes(count: Int) = JcaShareCrypto.randomBytes(count)
        override fun hmacSha512(key: ByteArray, data: ByteArray) = JcaShareCrypto.hmacSha512(key, data)
        override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray) =
            JcaShareCrypto.aesGcmSeal(key, nonce, aad, plaintext)
        override fun createSigningKey(alias: String) = error("Keystore only")
        override fun sign(alias: String, data: ByteArray) = error("Keystore only")
        override fun putSecret(alias: String, value: ByteArray?) = error("Keystore only")
        override fun secret(alias: String) = error("Keystore only")
    }

    /** RFC 9605 appendix C.3, cipher suite 0x0005 (AES_256_GCM_SHA512_128). */
    @Test
    fun sealingMatchesTheRfcVector() {
        val sealer = SFrameSealer(cipher, "000102030405060708090a0b0c0d0e0f".unhex(), kid = 0x123)
        val frame = sealer.seal(
            ctr = 0x4567,
            metadata = "4945544620534672616d65205747".unhex(),
            plaintext = "64726166742d696574662d736672616d652d656e63".unhex(),
        )

        assertEquals("990123456794f509d36e9beacb0e261d99c7d1e972f1fed787d4049f17ca21353c1cc24d56ceabced279", frame.hex())
    }

    @Test
    fun derSignaturesBecomeRawOnesThatVerify() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val data = "hashiru".encodeToByteArray()
        repeat(20) {
            val der = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(data)
                sign()
            }
            val raw = JcaShareCrypto.derToRaw(der)
            assertEquals(64, raw.size)
            // P1363 is r ‖ s; the JDK verifies that form too.
            val verified = Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(pair.public)
                update(data)
                verify(raw)
            }
            assertTrue(verified)
        }
        assertEquals(65, JcaShareCrypto.uncompressed(pair.public as ECPublicKey).size)
    }
}
