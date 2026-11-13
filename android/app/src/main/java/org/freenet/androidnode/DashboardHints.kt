package org.freenet.androidnode

import java.net.HttpURLConnection
import java.net.URL

internal object DashboardHints {
    fun natHint(): String? {
        return runCatching {
            val connection = URL("http://127.0.0.1:7509/").openConnection() as HttpURLConnection
            connection.connectTimeout = 800
            connection.readTimeout = 800
            connection.instanceFollowRedirects = false
            try {
                if (connection.responseCode !in 200..299) return null
                val html = connection.inputStream.bufferedReader().use { it.readText() }
                natHintFromHtml(html)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}

internal fun natHintFromHtml(html: String): String? {
    val text = html.lowercase()
    return when {
        "only connected to gateways" in text || "gateway only" in text ->
            "Only connected to gateways — no peer-to-peer connections yet"
        "nat traversal is failing" in text || "nat traversal failing" in text ->
            "Connected but NAT traversal is failing"
        else -> null
    }
}
