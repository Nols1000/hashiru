package com.github.nols1000.hashiru.share.adapter

import com.github.nols1000.hashiru.iosinterop.hashiruDataTaskWithRequest
import com.github.nols1000.hashiru.iosinterop.hashiruSetHTTPBody
import com.github.nols1000.hashiru.iosinterop.hashiruSetHTTPHeaderField
import com.github.nols1000.hashiru.iosinterop.hashiruSetHTTPMethod
import com.github.nols1000.hashiru.toByteArray
import com.github.nols1000.hashiru.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [HttpTransport] over a URLSession made from [configuration], an ephemeral one unless a test stubs the network.
 * Its completion handlers run on a queue of the session's; the continuation takes the answer back to the caller's
 * dispatcher, and drops it once the caller gave up.
 */
@OptIn(ExperimentalForeignApi::class)
class UrlSessionTransport(
    configuration: NSURLSessionConfiguration = NSURLSessionConfiguration.ephemeralSessionConfiguration(),
) : HttpTransport {
    private val session = NSURLSession.sessionWithConfiguration(configuration, null, null)

    override suspend fun exchange(method: String, url: String, body: ByteArray?, bearerToken: String?): HttpResponse {
        val request = NSMutableURLRequest.requestWithURL(NSURL(string = url))
        hashiruSetHTTPMethod(request, method)
        body?.let { hashiruSetHTTPBody(request, it.toNSData()) }
        bearerToken?.let { hashiruSetHTTPHeaderField(request, "Bearer $it", "Authorization") }
        return suspendCancellableCoroutine { continuation ->
            val task = hashiruDataTaskWithRequest(session, request) { data, response, error ->
                val http = response as? NSHTTPURLResponse
                if (error == null && http != null) {
                    continuation.resume(HttpResponse(http.statusCode.toInt(), data?.toByteArray() ?: ByteArray(0)))
                } else {
                    continuation.resumeWithException(IllegalStateException(error?.localizedDescription ?: "no HTTP response"))
                }
            }
            if (task == null) {
                continuation.resumeWithException(IllegalStateException("no data task for $url"))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }
}
