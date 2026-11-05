package org.freenet.androidnode

import java.net.HttpURLConnection
import java.net.URL

/**
 * The prebuilt node has no JNI status call. Its dashboard renders the live
 * counters in the page title and the peer table, so read those two values.
 */
internal object DashboardStatus {
    data class Counts(
        val connections: Int,
        val peers: Int,
        val bytesSent: Long,
        val bytesReceived: Long,
        val uploadKbps: Double,
        val downloadKbps: Double,
    )

    private const val DASHBOARD = "http://127.0.0.1:7509/"
    private val titlePattern = Regex("""\((\d+)\)\s*Dashboard""")
    private val peerRowPattern = Regex("""class="peer-row"""")
    private val peerRows = Regex("""<tr class="peer-row".*?</tr>""", RegexOption.DOT_MATCHES_ALL)
    private val numericSort = Regex("""data-sort="(\d+)"""")
    private val uploadPattern = Regex("""Upload\s*<b>([\d.]+)\s*KB/s</b>""")
    private val downloadPattern = Regex("""Download\s*<b>([\d.]+)\s*KB/s</b>""")

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
            var sent = 0L
            var received = 0L
            peerRows.findAll(html).forEach { row ->
                val numbers = numericSort.findAll(row.value)
                    .mapNotNull { it.groupValues[1].toLongOrNull() }
                    .toList()
                if (numbers.size >= 3) {
                    sent += numbers[numbers.size - 3]
                    received += numbers[numbers.size - 2]
                }
            }
            Counts(
                connections = connections,
                peers = peers,
                bytesSent = sent,
                bytesReceived = received,
                uploadKbps = uploadPattern.find(html)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toDoubleOrNull()
                    ?: 0.0,
                downloadKbps = downloadPattern.find(html)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toDoubleOrNull()
                    ?: 0.0,
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
