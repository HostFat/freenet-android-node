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
                when {
                    html.contains("Only connected to gateways") ->
                        "Only connected to gateways — no peer-to-peer connections yet"
                    html.contains("NAT traversal is failing") ->
                        "Connected but NAT traversal is failing"
                    else -> null
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}
