package org.freenet.androidnode

import java.io.ByteArrayOutputStream
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

internal fun readBoundedText(reader: Reader, maxChars: Int): String {
    if (maxChars <= 0) return ""
    val out = StringBuilder(minOf(maxChars, 8 * 1024))
    val buffer = CharArray(4096)
    var remaining = maxChars
    while (remaining > 0) {
        val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        out.append(buffer, 0, read)
        remaining -= read
    }
    return out.toString()
}

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
                ?.use { readBoundedText(it, MAX_RESPONSE_CHARS) }
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
