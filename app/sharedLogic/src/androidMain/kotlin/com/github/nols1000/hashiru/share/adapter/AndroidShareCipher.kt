package com.github.nols1000.hashiru.share.adapter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.github.nols1000.hashiru.share.port.ShareCipherPort
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Share cryptography from `javax.crypto`, with signing keys in the Android Keystore, where they cannot be read
 * out. Secrets are kept in private preferences, sealed under a Keystore key of their own.
 */
class AndroidShareCipher(context: Context) : ShareCipherPort {
    private val preferences = context.applicationContext.getSharedPreferences("share-secrets", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    override fun randomBytes(count: Int): ByteArray = JcaShareCrypto.randomBytes(count)

    override fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray = JcaShareCrypto.hmacSha512(key, data)

    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        JcaShareCrypto.aesGcmSeal(key, nonce, aad, plaintext)

    override fun createSigningKey(alias: String): ByteArray {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(signingAlias(alias), KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return JcaShareCrypto.uncompressed(generator.generateKeyPair().public as ECPublicKey)
    }

    override fun sign(alias: String, data: ByteArray): ByteArray? {
        val key = keyStore.getKey(signingAlias(alias), null) as? PrivateKey ?: return null
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }
        return JcaShareCrypto.derToRaw(der)
    }

    override fun putSecret(alias: String, value: ByteArray?) {
        if (value == null) {
            preferences.edit().remove(alias).commit()
            if (keyStore.containsAlias(signingAlias(alias))) keyStore.deleteEntry(signingAlias(alias))
            return
        }
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val sealed = cipher.iv + cipher.doFinal(value)
        // Synchronously, as the engine relies on the key once this returns.
        preferences.edit().putString(alias, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
    }

    override fun secret(alias: String): ByteArray? {
        val sealed = preferences.getString(alias, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val key = keyStore.getKey(WRAPPING_ALIAS, null) as? SecretKey ?: return null
        return runCatching {
            Cipher.getInstance(AES_GCM).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
                doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
            }
        }.getOrNull()
    }

    private fun wrappingKey(): SecretKey {
        (keyStore.getKey(WRAPPING_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(WRAPPING_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun signingAlias(alias: String) = "hashiru.$alias.signing"

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val WRAPPING_ALIAS = "hashiru.share.wrapping"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}

/** The primitives that need no Keystore, apart so they can be checked against test vectors off the device. */
internal object JcaShareCrypto {
    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA512").run {
            init(SecretKeySpec(key, "HmacSHA512"))
            doFinal(data)
        }

    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(plaintext)
        }

    /** `04 ‖ x ‖ y`, each coordinate 32 bytes. */
    fun uncompressed(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)

    /** An ASN.1 DER `SEQUENCE { INTEGER r, INTEGER s }` as r ‖ s, 32 bytes each. */
    fun derToRaw(der: ByteArray): ByteArray {
        require(der[0] == 0x30.toByte()) { "not a DER sequence" }
        var at = if (der[1].toInt() and 0x80 != 0) 2 + (der[1].toInt() and 0x7F) else 2
        fun integer(): BigInteger {
            require(der[at] == 0x02.toByte()) { "not a DER integer" }
            val length = der[at + 1].toInt()
            return BigInteger(1, der.copyOfRange(at + 2, at + 2 + length)).also { at += 2 + length }
        }
        return fixed(integer()) + fixed(integer())
    }

    private fun fixed(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32 - bytes.size) + bytes
    }
}
