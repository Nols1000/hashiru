package io.github.nols1000.release.common

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

class HttpException(val status: Int, message: String) : RuntimeException(message)

/** Minimal JSON-over-HTTPS client on top of the JDK, so the plugins need no HTTP or JSON dependencies. */
object Http {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun send(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: BodyPublisher = BodyPublishers.noBody(),
    ): String {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofMinutes(15))
            .method(method, body)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val response = client.send(request, BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw HttpException(response.statusCode(), "$method ${url.substringBefore('?')} failed with ${response.statusCode()}: ${response.body()}")
        }
        return response.body()
    }

    fun json(
        method: String,
        url: String,
        bearer: String? = null,
        body: Any? = null,
        headers: Map<String, String> = emptyMap(),
    ): Map<String, Any?> {
        val allHeaders = buildMap {
            put("Accept", "application/json")
            if (bearer != null) put("Authorization", "Bearer $bearer")
            if (body != null) put("Content-Type", "application/json")
            putAll(headers)
        }
        val publisher = if (body != null) BodyPublishers.ofString(Json.write(body)) else BodyPublishers.noBody()
        return Json.parse(send(method, url, allHeaders, publisher))
    }
}

object Json {
    @Suppress("UNCHECKED_CAST")
    fun parse(text: String): Map<String, Any?> =
        if (text.isBlank()) emptyMap() else JsonSlurper().parseText(text) as Map<String, Any?>

    fun write(value: Any?): String = JsonOutput.toJson(value)
}
