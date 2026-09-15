package org.freenet.androidnode

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.zip.GZIPOutputStream

internal const val SERVICE_REPORT_SERVER = "https://nova.locut.us/api/reports"
internal const val SERVICE_REPORT_REPO = "https://github.com/HostFat/freenet-android-node"
private const val MAX_LOG_BYTES = 2 * 1024 * 1024
private const val WS_API_PORT = 7509
private const val RING_LOG_ENTRIES = 256

internal fun presetServiceReportComment(appVersion: String): String =
    "This report was sent from the unofficial Android APK\n" +
        "$SERVICE_REPORT_REPO\n" +
        "App version: $appVersion\n"

internal fun appVersionName(context: Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.trim().orEmpty().ifBlank { "unknown" }

internal object ServiceReport {
    fun upload(context: Context, userMessage: String): Result<String> = runCatching {
        val json = buildReportJson(context, userMessage).toString()
        postGzip(json)
    }

    internal fun buildReportJson(context: Context, userMessage: String): JSONObject {
        val logs = collectLogs(context)
        val config = ConfigToml.read(context)
        val (networkStatus, networkError) = collectNetworkStatus()
        val coreInfo = NativeBridge.freenetBuildInfo().getOrNull().orEmpty()
        val coreVersion = coreVersionFromBuildInfo(coreInfo) ?: "unknown"
        val message = userMessage.trim().ifEmpty { null }
        return JSONObject()
            .put("client_timestamp", Instant.now().toString())
            .put(
                "system_info",
                JSONObject()
                    .put("os", "android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    .put("arch", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                    .put("hostname", "${Build.MANUFACTURER} ${Build.MODEL}".trim()),
            )
            .put(
                "version_info",
                JSONObject()
                    .put("version", coreVersion)
                    .put("git_commit", "android-apk")
                    .put("git_dirty", false)
                    .put("build_timestamp", "apk ${appVersionName(context)}"),
            )
            .put("logs", logs)
            .put("config", config.ifBlank { JSONObject.NULL })
            .put("network_status", networkStatus ?: JSONObject.NULL)
            .put("network_status_error", networkError ?: JSONObject.NULL)
            .put("user_message", message ?: JSONObject.NULL)
    }

    private fun collectLogs(context: Context): JSONObject {
        val logDir = File(context.filesDir, "freenet/logs")
        val files = logDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("freenet") && it.name.endsWith(".log") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        val fromFiles = if (files.isEmpty()) {
            null
        } else {
            files.joinToString("\n") { file ->
                runCatching { file.readText() }.getOrDefault("")
            }.trim().ifEmpty { null }
        }
        val fromRing = formatServiceReportLogs()
        val main = when {
            fromFiles != null && fromFiles.length > MAX_LOG_BYTES ->
                fromFiles.takeLast(MAX_LOG_BYTES)
            fromFiles != null -> fromFiles
            else -> fromRing
        }
        val bytes = main?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        return JSONObject()
            .put("main_log", main ?: JSONObject.NULL)
            .put("error_log", JSONObject.NULL)
            .put("main_log_size_bytes", bytes)
            .put("error_log_size_bytes", 0L)
            .put("main_log_original_size_bytes", bytes)
            .put("error_log_original_size_bytes", 0L)
    }

    private fun collectNetworkStatus(): Pair<String?, String?> {
        if (!NativeBridge.isLoaded) {
            return null to "Native library is not loaded"
        }
        val diagnostics = NativeBridge.queryNodeDiagnostics(WS_API_PORT)
        diagnostics.getOrNull()?.let { raw ->
            val envelope = runCatching { JSONObject(raw) }.getOrNull()
            if (envelope?.optBoolean("ok") == true) {
                val data = envelope.opt("data")
                return (data?.toString() ?: raw) to null
            }
            val error = envelope?.optJSONObject("error")?.optString("message")
                ?: envelope?.optString("error")
            if (!error.isNullOrBlank()) {
                return null to error
            }
        }
        val status = NativeBridge.nodeStatus().getOrNull()
        if (status != null) {
            val envelope = runCatching { JSONObject(status) }.getOrNull()
            if (envelope?.optBoolean("ok") == true) {
                return status to null
            }
            val detail = envelope?.optJSONObject("data")?.optString("detail")
            return null to (detail ?: "Node is not running")
        }
        return null to (diagnostics.exceptionOrNull()?.message ?: "Node diagnostics unavailable")
    }

    private fun postGzip(json: String): String {
        val compressed = ByteArrayOutputStream().use { bytes ->
            GZIPOutputStream(bytes).use { gzip ->
                gzip.write(json.toByteArray(Charsets.UTF_8))
            }
            bytes.toByteArray()
        }
        val connection = (URL(SERVICE_REPORT_SERVER).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Encoding", "gzip")
            setRequestProperty("User-Agent", "freenet-report")
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
            val code = JSONObject(body).optString("code").trim()
            require(code.isNotEmpty()) { "Upload succeeded but the server did not return a report code" }
            return code
        } finally {
            connection.disconnect()
        }
    }
}

internal fun formatServiceReportLogs(): String? {
    val raw = NativeBridge.recentLogs(RING_LOG_ENTRIES).getOrNull() ?: return null
    return runCatching {
        val entries = JSONObject(raw).getJSONObject("data").getJSONArray("entries")
        if (entries.length() == 0) return@runCatching null
        buildString {
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                append(entry.optString("level"))
                append("  ")
                append(entry.optString("message"))
                append('\n')
            }
        }.trimEnd()
    }.getOrNull()
}
