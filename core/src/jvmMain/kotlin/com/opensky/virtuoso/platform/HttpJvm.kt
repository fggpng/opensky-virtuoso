package com.opensky.virtuoso.platform

import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

actual suspend fun httpGet(url: String, timeoutMs: Int, userAgent: String): HttpResponse = withContext(ioDispatcher) {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", userAgent)
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        HttpResponse(code, text)
    } finally {
        conn.disconnect()
    }
}
