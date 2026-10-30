package com.github.nols1000.hashiru.share.port

import kotlinx.coroutines.flow.Flow

/** Driven port: the browser's cryptography (WebCrypto), which answers asynchronously. */
interface ViewerCryptoPort {
    /** HKDF-SHA-512 with an empty salt, extract and expand in one. */
    suspend fun hkdfSha512(ikm: ByteArray, info: ByteArray, length: Int): ByteArray

    suspend fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray

    /** AES-GCM with a 16-byte tag after the ciphertext; null when the tag does not match. */
    suspend fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray?

    /** ECDSA P-256 with SHA-256; [publicKey] uncompressed, [signature] raw r ‖ s. */
    suspend fun verifyP256(publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean
}

/** What a viewer's stream from the relay carries. */
sealed interface StreamEvent {
    /** A sealed, signed frame; a [snapshot] holds all a viewer needs. */
    class Frame(val seq: Long, val snapshot: Boolean, val bytes: ByteArray) : StreamEvent

    /** The walker ended the share. */
    data object Ended : StreamEvent

    /** The relay did not accept the presence proof, such as for a challenge already spent. */
    data object Denied : StreamEvent

    /** The share has as many viewers as the relay allows. */
    data object Full : StreamEvent
}

/** Driven port: the relay as viewers reach it. */
interface ShareStreamPort {
    /** A single-use nonce for [shareId]'s presence proof, or null when the relay does not know the share. */
    suspend fun challenge(shareId: String): String?

    /** The share's frames, the ones held first; completes or throws when the connection ends. */
    fun stream(shareId: String, nonce: String, proof: ByteArray): Flow<StreamEvent>
}
