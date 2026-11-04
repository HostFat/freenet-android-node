package org.freenet.androidnode

import java.net.HttpURLConnection
import java.net.URL

/**
 * The prebuilt node has no JNI status call. Its dashboard renders the live
 * counters in the page title and the peer table, so read those two values.
 */
internal object DashboardStatus {
    data class Counts(val connections: Int, val peers: Int)

    private const val DASHBOARD = "http://127.0.0.1:7509/"
    private val titlePattern = Regex("""\((\d+)\)\s*Dashboard""")
    private val peerRowPattern = Regex("""class="peer-row"""")

    fun read(): Counts? = runCatching {
        val connection = (URL(DASHBOARD).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 2_000
            setRequestProperty("User-Agent", "freenet-android-node")
        }
        try {
            if (connection.responseCode !in 200..299) {
                return@runCatching null
            }
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            val connections = titlePattern.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0
            val peers = peerRowPattern.findAll(html).count()
            Counts(connections = connections, peers = peers)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
