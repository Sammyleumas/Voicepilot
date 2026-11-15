package com.voicepilot.ai.platform

import com.voicepilot.core.llm.HttpResponse
import com.voicepilot.core.llm.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal HTTPS transport on HttpURLConnection, avoiding an extra HTTP client
 * dependency. Cleartext is blocked by the network security config.
 */
@Singleton
class HttpUrlConnectionTransport @Inject constructor() : HttpTransport {

    override suspend fun post(url: String, headers: Map<String, String>, body: String, timeoutMillis: Long): HttpResponse =
        runInterruptible(Dispatchers.IO) {
            require(url.startsWith("https://")) { "Only HTTPS is allowed" }
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = timeoutMillis.toInt().coerceAtMost(15_000)
                connection.readTimeout = timeoutMillis.toInt()
                connection.doOutput = true
                connection.useCaches = false
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val sb = StringBuilder()
                    val buf = CharArray(4096)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                        require(sb.length <= MAX_RESPONSE_CHARS) { "Response too large" }
                    }
                    sb.toString()
                }.orEmpty()
                HttpResponse(code, text)
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val MAX_RESPONSE_CHARS = 512 * 1024
    }
}
