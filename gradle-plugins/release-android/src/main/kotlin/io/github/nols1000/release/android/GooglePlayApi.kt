package io.github.nols1000.release.android

import io.github.nols1000.release.common.Http
import io.github.nols1000.release.common.Json
import java.io.File
import java.net.http.HttpRequest.BodyPublishers

/** The few Google Play Developer API v3 calls needed to release a bundle to a track. */
class GooglePlayApi(private val accessToken: String, private val packageName: String) {
    private val app = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$packageName"
    private val upload = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$packageName"

    fun insertEdit(): String =
        Http.json("POST", "$app/edits", accessToken, body = emptyMap<String, Any>())["id"] as String

    /** Uploads the bundle and returns its version code. */
    fun uploadBundle(editId: String, bundle: File): Long {
        val response = Http.send(
            "POST",
            "$upload/edits/$editId/bundles?uploadType=media",
            mapOf("Authorization" to "Bearer $accessToken", "Content-Type" to "application/octet-stream"),
            BodyPublishers.ofFile(bundle.toPath()),
        )
        return (Json.parse(response)["versionCode"] as Number).toLong()
    }

    fun updateTrack(editId: String, track: String, releaseName: String, versionCode: Long, status: String) {
        Http.json(
            "PUT",
            "$app/edits/$editId/tracks/$track",
            accessToken,
            body = mapOf(
                "track" to track,
                "releases" to listOf(
                    mapOf("name" to releaseName, "versionCodes" to listOf(versionCode.toString()), "status" to status),
                ),
            ),
        )
    }

    fun commit(editId: String) {
        Http.json("POST", "$app/edits/$editId:commit", accessToken)
    }

    fun deleteEdit(editId: String) {
        Http.send("DELETE", "$app/edits/$editId", mapOf("Authorization" to "Bearer $accessToken"))
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/androidpublisher"
    }
}
