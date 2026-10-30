package com.github.nols1000.hashiru.share.adapter

import com.github.nols1000.hashiru.share.port.ViewerCryptoPort
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

/** The few WebCrypto calls the viewer makes; `crypto.subtle` in browsers and in Node. */
private external interface SubtleCrypto {
    fun importKey(format: String, keyData: Uint8Array, algorithm: dynamic, extractable: Boolean, keyUsages: Array<String>): Promise<dynamic>
    fun deriveBits(algorithm: dynamic, baseKey: dynamic, length: Int): Promise<ArrayBuffer>
    fun sign(algorithm: dynamic, key: dynamic, data: Uint8Array): Promise<ArrayBuffer>
    fun verify(algorithm: dynamic, key: dynamic, signature: Uint8Array, data: Uint8Array): Promise<Boolean>
    fun decrypt(algorithm: dynamic, key: dynamic, data: Uint8Array): Promise<ArrayBuffer>
}

private val subtle: SubtleCrypto get() = js("globalThis.crypto.subtle").unsafeCast<SubtleCrypto>()

private fun ByteArray.toUint8Array(): Uint8Array = unsafeCast<Int8Array>().let { Uint8Array(it.buffer, it.byteOffset, it.length) }

private fun ArrayBuffer.toByteArray(): ByteArray = Int8Array(this).unsafeCast<ByteArray>()

private fun algorithm(vararg entries: Pair<String, Any>): dynamic {
    val o: dynamic = js("({})")
    for ((key, value) in entries) o[key] = value
    return o
}

/** [ViewerCryptoPort] over WebCrypto; no key leaves it in readable form once imported. */
class WebCryptoViewerCrypto : ViewerCryptoPort {
    override suspend fun hkdfSha512(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val material = subtle.importKey("raw", ikm.toUint8Array(), "HKDF", false, arrayOf("deriveBits")).await()
        val params = algorithm("name" to "HKDF", "hash" to "SHA-512", "salt" to Uint8Array(0), "info" to info.toUint8Array())
        return subtle.deriveBits(params, material, length * 8).await().toByteArray()
    }

    override suspend fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val hmac = algorithm("name" to "HMAC", "hash" to "SHA-512")
        val imported = subtle.importKey("raw", key.toUint8Array(), hmac, false, arrayOf("sign")).await()
        return subtle.sign("HMAC", imported, data.toUint8Array()).await().toByteArray()
    }

    override suspend fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? {
        val imported = subtle.importKey("raw", key.toUint8Array(), "AES-GCM", false, arrayOf("decrypt")).await()
        val params = algorithm("name" to "AES-GCM", "iv" to nonce.toUint8Array(), "additionalData" to aad.toUint8Array(), "tagLength" to 128)
        // WebCrypto rejects when the tag does not match.
        return runCatching { subtle.decrypt(params, imported, ciphertext.toUint8Array()).await().toByteArray() }.getOrNull()
    }

    override suspend fun verifyP256(publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean {
        val curve = algorithm("name" to "ECDSA", "namedCurve" to "P-256")
        val imported = runCatching { subtle.importKey("raw", publicKey.toUint8Array(), curve, false, arrayOf("verify")).await() }.getOrNull()
            ?: return false
        return subtle.verify(algorithm("name" to "ECDSA", "hash" to "SHA-256"), imported, signature.toUint8Array(), data.toUint8Array()).await()
    }
}
