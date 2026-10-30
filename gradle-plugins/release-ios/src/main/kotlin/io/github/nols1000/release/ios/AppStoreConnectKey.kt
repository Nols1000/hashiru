package io.github.nols1000.release.ios

import io.github.nols1000.release.common.Json
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

/** An App Store Connect API key (`AuthKey_<id>.p8`). */
class AppStoreConnectKey(val keyFile: File, val keyId: String, val issuerId: String) {
    /** Lets xcodebuild manage certificates and profiles and upload builds with this key. */
    fun xcodebuildArgs() = listOf(
        "-authenticationKeyPath", keyFile.absolutePath,
        "-authenticationKeyID", keyId,
        "-authenticationKeyIssuerID", issuerId,
    )

    /** A short-lived ES256 token for the App Store Connect API. */
    fun jwt(): String {
        val now = Instant.now().epochSecond
        val header = base64Url(Json.write(mapOf("alg" to "ES256", "kid" to keyId, "typ" to "JWT")).toByteArray())
        val payload = base64Url(
            Json.write(mapOf("iss" to issuerId, "iat" to now, "exp" to now + 600, "aud" to "appstoreconnect-v1")).toByteArray()
        )
        val pem = keyFile.readLines().filterNot { it.startsWith("-----") }.joinToString("")
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(privateKey)
            update("$header.$payload".toByteArray())
            sign()
        }
        return "$header.$payload.${base64Url(signature)}"
    }

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        /** The key CI decodes from the `testflight` environment, or null to use the Xcode account instead. */
        fun fromEnvironment(): AppStoreConnectKey? {
            val path = System.getenv("ASC_KEY_PATH") ?: return null
            return AppStoreConnectKey(File(path), System.getenv("ASC_KEY_ID"), System.getenv("ASC_ISSUER_ID"))
        }
    }
}
