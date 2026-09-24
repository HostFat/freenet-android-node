package org.freenet.androidnode

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

internal const val CRASH_INBOX_URL =
    "https://freenet-android-crash-inbox.hostfat.workers.dev/v1/crash"

internal fun presetCrashReportComment(appVersion: String): String =
    "This crash report was sent from the unofficial Android APK\n" +
        "$SERVICE_REPORT_REPO\n" +
        "App version: $appVersion\n"

internal object CrashInbox {
    fun upload(context: android.content.Context, userMessage: String): Result<String> = runCatching {
        val token = BuildConfig.CRASH_REPORT_TOKEN.trim()
        require(token.isNotEmpty()) { "Crash reports are not configured in this build" }
        val json = ServiceReport.buildReportJson(context, userMessage).toString()
        val compressed = ByteArrayOutputStream().use { bytes ->
            GZIPOutputStream(bytes).use { gzip ->
                gzip.write(json.toByteArray(Charsets.UTF_8))
            }
            bytes.toByteArray()
        }
        val connection = (URL(CRASH_INBOX_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Encoding", "gzip")
            setRequestProperty("X-App-Version", appVersionName(context))
        }
        try {
            connection.outputStream.use { it.write(compressed) }
            val body = (if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            })?.bufferedReader()?.readText().orEmpty()
            if (connection.responseCode !in 200..299) {
                error("Upload failed: ${connection.responseCode} $body")
            }
            val id = JSONObject(body).optString("id").trim()
            require(id.isNotEmpty()) { "Upload succeeded but the inbox did not return an id" }
            id
        } finally {
            connection.disconnect()
        }
    }
}
