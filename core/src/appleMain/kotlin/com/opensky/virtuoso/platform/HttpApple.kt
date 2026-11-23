@file:OptIn(BetaInteropApi::class)

package com.opensky.virtuoso.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual suspend fun httpGet(url: String, timeoutMs: Int, userAgent: String): HttpResponse {
    val nsUrl = NSURL.URLWithString(url) ?: throw IOException("URL no válida: $url")
    val request = NSMutableURLRequest.requestWithURL(nsUrl)
    request.setTimeoutInterval(timeoutMs / 1000.0)
    request.setValue(userAgent, forHTTPHeaderField = "User-Agent")
    return suspendCancellableCoroutine { cont ->
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            if (error != null) {
                cont.resumeWithException(IOException(error.localizedDescription))
            } else {
                val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                val body = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() } ?: ""
                cont.resume(HttpResponse(code, body))
            }
        }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }
}
