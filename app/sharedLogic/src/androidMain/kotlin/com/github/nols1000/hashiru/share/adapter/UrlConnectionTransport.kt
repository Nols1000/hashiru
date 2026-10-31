package com.github.nols1000.hashiru.share.adapter

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * [HttpTransport] over `HttpURLConnection`, on the IO dispatcher. Cancelling the caller disconnects, which ends a
 * read or write blocked on the socket; otherwise the connection stays open for the next exchange to reuse.
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {
    override suspend fun exchange(method: String, url: String, body: ByteArray?, bearerToken: String?): HttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            val exchange = coroutineContext.job
            val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (exchange.isCancelled) connection.disconnect()
                }
            }
            try {
                connection.exchange(method, body, bearerToken)
            } catch (e: IOException) {
                // Disconnecting fails the read; the caller gave up, so it hears of that instead.
                ensureActive()
                throw e
            } finally {
                watchdog.cancel()
            }
        }

    private fun HttpURLConnection.exchange(method: String, body: ByteArray?, bearerToken: String?): HttpResponse {
        requestMethod = method
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        useCaches = false
        bearerToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(body.size)
            outputStream.use { it.write(body) }
        }
        val status = responseCode
        // Answers from 400 up come on the error stream, which is null when they have no body.
        val stream = if (status >= 400) errorStream else inputStream
        return HttpResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
    }
}
