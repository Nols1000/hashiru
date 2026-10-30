package com.github.nols1000.hashiru.share.codec

/**
 * What a share link carries, all of it in the fragment, which browsers never send: neither the static host of
 * the viewer nor the relay learns from a request which share someone opens.
 *
 * ```
 * https://<viewer>/#v1.<share id>.<base key>.<signing public key>     (unpadded URL-safe base64)
 * ```
 */
class ShareLink(val shareId: String, val baseKey: ByteArray, val signingKey: ByteArray) {
    /** The fragment, without `#`. */
    fun fragment(): String = "$VERSION.$shareId.${Base64Url.encode(baseKey)}.${Base64Url.encode(signingKey)}"

    /** The link under [viewerUrl], with or without a trailing slash. */
    fun url(viewerUrl: String): String = "${viewerUrl.trimEnd('/')}/#${fragment()}"

    companion object {
        private const val VERSION = "v1"
        private val ID = Regex("[A-Za-z0-9_-]{22}")

        /** Null for anything but a whole v1 fragment, with or without `#`. */
        fun parse(fragment: String): ShareLink? {
            val parts = fragment.removePrefix("#").split('.')
            if (parts.size != 4 || parts[0] != VERSION || !ID.matches(parts[1])) return null
            val baseKey = Base64Url.decode(parts[2])?.takeIf { it.size == SFrame.KEY_BYTES } ?: return null
            val signingKey = Base64Url.decode(parts[3])?.takeIf { it.size == 65 && it[0] == 4.toByte() } ?: return null
            return ShareLink(parts[1], baseKey, signingKey)
        }
    }
}
