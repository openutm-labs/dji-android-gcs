package org.openutm.djiflight.core.http

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

class HttpResponse(val status: Int, val body: String)

/**
 * Blocking HTTP, called from an IO dispatcher. Kept to one method so tests can fake it, and so
 * the core needs no HTTP library beside the DJI SDK's own (HttpURLConnection exists on JVM and Android).
 */
fun interface HttpTransport {
    /** Throws IOException when no HTTP response was received. */
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.useCaches = false
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (request.body != null) {
                conn.doOutput = true
                // No setFixedLengthStreamingMode: a buffered body (at most ~180 KB) can be replayed, so
                // Android's HttpURLConnection retries by itself when it reuses a keep-alive connection
                // the server already closed. A streamed body made that an "unexpected end of stream"
                // failure and a sync backoff on the emulator (2026-10-16).
                conn.outputStream.use { it.write(request.body) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResponse(status, body)
        } finally {
            conn.disconnect()
        }
    }
}
