package org.freenet.androidnode

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/** Shared gzip JSON POST used by the crash inbox and the service report. */
internal object HttpJson {
    private const val MAX_RESPONSE_CHARS = 64 * 1024

    fun postGzip(
        url: String,
        headers: Map<String, String>,
        json: String,
        connectTimeoutMs: Int = 30_000,
        readTimeoutMs: Int = 30_000,
    ): Result<String> = runCatching {
        val compressed = gzip(json)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Encoding", "gzip")
            for ((name, value) in headers) {
                setRequestProperty(name, value)
            }
        }
        try {
            connection.outputStream.use { it.write(compressed) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText().take(MAX_RESPONSE_CHARS) }
                .orEmpty()
            if (code !in 200..299) {
                error("Upload failed: $code $body")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun gzip(json: String): ByteArray = ByteArrayOutputStream().use { bytes ->
        GZIPOutputStream(bytes).use { gzip ->
            gzip.write(json.toByteArray(Charsets.UTF_8))
        }
        bytes.toByteArray()
    }
}
