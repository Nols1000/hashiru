package com.github.nols1000.hashiru.share.port

/**
 * Driven port: the platform's cryptography (CryptoKit and the Keychain on iOS, `javax.crypto` and the Android
 * Keystore on Android). Shared code builds SFrame, HKDF and the share's keys from these primitives; the signing
 * key never leaves the platform, and secrets are stored where the platform keeps keys.
 */
interface ShareCipherPort {
    fun randomBytes(count: Int): ByteArray

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray

    /** AES-GCM with a 16-byte tag; returns the ciphertext followed by the tag. */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /** Makes a P-256 signing key kept under [alias], replacing any; returns its public key uncompressed (65 bytes). */
    fun createSigningKey(alias: String): ByteArray

    /** ECDSA P-256 with SHA-256 over [data], as raw r ‖ s (64 bytes), which WebCrypto verifies. Null without the key. */
    fun sign(alias: String, data: ByteArray): ByteArray?

    /** Stores [value] under [alias], or removes it, together with any signing key of that alias, when it is null. */
    fun putSecret(alias: String, value: ByteArray?)

    fun secret(alias: String): ByteArray?
}
