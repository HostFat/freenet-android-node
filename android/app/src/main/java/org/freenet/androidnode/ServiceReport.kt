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
private const val MAX_LOG_BYTES = 400 * 1024
private const val WS_API_PORT = 7509
private const val RING_LOG_ENTRIES = 256

/** Keeps the shape of config.toml but replaces path values and long tokens. */
internal fun redactConfigToml(text: String): String =
    text.lines().joinToString("\n") { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("#") || trimmed.startsWith("[")) {
            return@joinToString line
        }
        val separator = line.indexOf('=')
        if (separator < 0) {
            return@joinToString line
        }
        val key = line.substring(0, separator).trimEnd()
        val rawValue = line.substring(separator + 1).trim()
        if (key.isNotEmpty() && valueIsLocalMaterial(unquoteToml(rawValue))) {
            "$key = \"<redacted>\""
        } else {
            line
        }
    }

private fun unquoteToml(value: String): String {
    if (value.length >= 2 &&
        ((value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'")))
    ) {
        return value.substring(1, value.length - 1)
    }
    return value
}

private fun valueIsLocalMaterial(value: String): Boolean {
    if (value.isEmpty()) {
        return false
    }
    if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
        return true
    }
    return value.length >= 40 && value.all { character ->
        character.isLetterOrDigit() || character == '+' || character == '=' || character == '-' || character == '_'
    }
}

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

    internal fun buildReportJson(
        context: Context,
        userMessage: String,
        extraLog: String? = null,
    ): JSONObject {
        val logs = collectLogs(context, extraLog)
        val config = ConfigToml.read(context)
        val lastState = NodeRepository.state.value
        val (networkStatus, networkError) = collectNetworkStatus(lastState)
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
            .put("config", redactConfigToml(config).ifBlank { JSONObject.NULL })
            .put("network_status", networkStatus ?: JSONObject.NULL)
            .put("network_status_error", networkError ?: JSONObject.NULL)
            .put("user_message", message ?: JSONObject.NULL)
    }

    internal fun hasLogFiles(context: Context): Boolean {
        val logDir = File(context.filesDir, "freenet/logs")
        return logDir.listFiles()?.any { file ->
            file.isFile && file.name.startsWith("freenet") && file.name.endsWith(".log") && file.length() > 0L
        } == true
    }

    internal fun readFileLogs(context: Context): String? {
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
        return fromFiles
    }

    private fun collectLogs(context: Context, extraLog: String? = null): JSONObject {
        val fromFiles = readFileLogs(context)
        val fromRing = formatServiceReportLogs()
        val merged = listOfNotNull(extraLog, fromFiles, fromRing).joinToString(
            "\n--- android adapter ring ---\n",
        ).ifBlank { null }
        val main = when {
            merged != null && merged.length > MAX_LOG_BYTES -> merged.takeLast(MAX_LOG_BYTES)
            else -> merged
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

    private fun collectNetworkStatus(lastState: NodeUiState): Pair<String?, String?> {
        val fallback = lastKnownStatusJson(lastState)
        if (!NativeBridge.isLoaded) {
            return fallback to "Native library is not loaded"
        }
        val diagnostics = runCatching {
            NativeBridge.queryNodeDiagnostics(WS_API_PORT).getOrThrow()
        }
        diagnostics.getOrNull()?.let { raw ->
            val envelope = runCatching { JSONObject(raw) }.getOrNull()
            if (envelope?.optBoolean("ok") == true) {
                val data = envelope.opt("data")
                return (data?.toString() ?: raw) to null
            }
            val error = envelope?.optJSONObject("error")?.optString("message")
                ?.ifBlank { null }
                ?: envelope?.optString("error")?.ifBlank { null }
            return fallback to (error ?: "Node diagnostics unavailable")
        }
        val status = runCatching { NativeBridge.nodeStatus().getOrThrow() }.getOrNull()
        if (status != null) {
            val envelope = runCatching { JSONObject(status) }.getOrNull()
            if (envelope?.optBoolean("ok") == true) {
                return status to null
            }
        }
        val reason = diagnostics.exceptionOrNull()?.message
            ?: "Node is not running or did not answer"
        return fallback to reason
    }

    internal fun lastKnownStatusJson(state: NodeUiState): String =
        JSONObject()
            .put("source", "android-last-known")
            .put("state", state.state.wire)
            .put("detail", state.detail)
            .put("mode", state.mode.wire)
            .put("peers", state.peers)
            .put("lastNetworkError", state.lastNetworkError ?: JSONObject.NULL)
            .put("udpPortInUse", state.udpPortInUse)
            .put("serviceActive", state.serviceActive)
            .toString(2)

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
            // A service report uses its own token, never the crash token.
            BuildConfig.SERVICE_REPORT_TOKEN.trim().takeIf { it.isNotEmpty() }?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
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
