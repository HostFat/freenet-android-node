package org.freenet.androidnode

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.time.Instant

internal const val SERVICE_REPORT_SERVER = "https://nova.locut.us/api/reports"
internal const val SERVICE_REPORT_REPO = "https://github.com/HostFat/freenet-android-node"
private const val MAX_LOG_BYTES = 400 * 1024
private const val MAX_LOG_READ_BYTES = MAX_LOG_BYTES
private const val MAX_TOTAL_LOG_READ_BYTES = MAX_LOG_BYTES * 4
private const val MAX_LOG_FILES = 8
private const val HEAD_LOG_BYTES = 64 * 1024
private const val WS_API_PORT = 7509
private const val RING_LOG_ENTRIES = 256

internal fun isRateLimitLogLine(line: String): Boolean = line.contains("[RATE LIMIT")

internal fun filterLogNoise(text: String): String {
    var dropped = 0
    val kept = text.lineSequence().filter { line ->
        if (isRateLimitLogLine(line)) {
            dropped += 1
            false
        } else {
            true
        }
    }.toList()
    if (dropped == 0) return text
    return kept.joinToString("\n") + "\n[filtered $dropped rate-limit log lines]"
}

private val REDACTED_CONFIG_KEYS = setOf(
    "transport_keypair",
    "secrets_dir",
    "config_dir",
    "data_dir",
    "db_dir",
    "contracts_dir",
    "delegates_dir",
    "event_log",
    "log_dir",
    "wasmtime_cache_dir",
    "cipher",
    "secret_key",
    "private_key",
    "auth_token",
    "token",
)

private val ANDROID_PRIVATE_PATH = Regex("""/data/(?:user/\d+|data)/[^\s"']+""")

/** Keeps the shape of config.toml but replaces local paths and identities. */
internal fun redactConfigToml(text: String): String =
    text.lines().joinToString("\n") { line ->
        val rawKey = line.substringBefore('=').trim()
        val normalizedKey = rawKey.replace('-', '_').lowercase()
        when {
            rawKey.isNotEmpty() && line.contains('=') && normalizedKey in REDACTED_CONFIG_KEYS ->
                "$rawKey = \"<redacted>\""
            ANDROID_PRIVATE_PATH.containsMatchIn(line) ->
                ANDROID_PRIVATE_PATH.replace(line, "<redacted>")
            else -> line
        }
    }

internal fun redactLogText(text: String): String =
    ANDROID_PRIVATE_PATH.replace(text, "<redacted>")

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
        postGzip(context, json)
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
        val coreVersion = NodeBackend.coreVersion() ?: "unknown"
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
            .put("install_id", InstallId.get(context))
    }

    private fun logDirectory(context: Context): File =
        if (BuildConfig.PREBUILT_NODE) {
            File(context.filesDir, "freenet/prebuilt/logs")
        } else {
            File(context.filesDir, "freenet/logs")
        }

    internal fun hasLogFiles(context: Context): Boolean {
        val logDir = logDirectory(context)
        return logDir.listFiles()?.any { file ->
            file.isFile && file.name.endsWith(".log") && file.length() > 0L
        } == true
    }

    internal fun readFileLogs(context: Context): String? {
        val logDir = logDirectory(context)
        val files = logDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") && it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_LOG_FILES)
            .orEmpty()
        if (files.isEmpty()) return null
        var remaining = MAX_TOTAL_LOG_READ_BYTES
        val chunks = ArrayList<String>(files.size)
        for (file in files) {
            if (remaining <= 0) break
            val budget = minOf(MAX_LOG_READ_BYTES, remaining)
            val chunk = filterLogNoise(readLogTail(file, budget))
            if (chunk.isNotBlank()) {
                chunks.add(chunk)
                remaining -= minOf(budget.toLong(), file.length()).toInt()
            }
        }
        if (chunks.isEmpty()) return null
        return filterLogNoise(chunks.asReversed().joinToString("\n"))
            .takeLast(MAX_LOG_BYTES)
            .trim()
            .ifEmpty { null }
    }

    private fun collectLogs(context: Context, extraLog: String? = null): JSONObject {
        val fromFiles = readFileLogs(context)
        val fromRing = formatServiceReportLogs()
        val mergedRaw = listOfNotNull(extraLog, fromFiles, fromRing).joinToString(
            "\n--- android adapter ring ---\n",
        ).ifBlank { null }
        val merged = mergedRaw?.let(::filterLogNoise)?.let(::redactLogText)
        val mergedUtf8Bytes = merged?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val truncated = mergedUtf8Bytes > MAX_LOG_BYTES
        val main = if (truncated && merged != null) {
            truncateTailUtf8(merged, MAX_LOG_BYTES)
        } else {
            merged
        }
        val head = if (truncated && merged != null) {
            truncateHeadUtf8(merged, HEAD_LOG_BYTES)
        } else {
            null
        }
        val originalBytes = mergedRaw?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        val bytes = main?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        return JSONObject()
            .put("main_log", main ?: JSONObject.NULL)
            .put("main_log_head", head ?: JSONObject.NULL)
            .put("main_log_truncated", truncated)
            .put("error_log", JSONObject.NULL)
            .put("main_log_size_bytes", bytes)
            .put("error_log_size_bytes", 0L)
            .put("main_log_original_size_bytes", originalBytes)
            .put("error_log_original_size_bytes", 0L)
            .put("app_events", ServiceEventLog.recent() ?: JSONObject.NULL)
            .put(
                "node_exit_detail",
                if (BuildConfig.PREBUILT_NODE) {
                    PrebuiltNodeProcess.lastExitDetail()?.let(::redactLogText) ?: JSONObject.NULL
                } else {
                    JSONObject.NULL
                },
            )
    }

    private fun truncateTailUtf8(text: String, maxBytes: Int): String {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size <= maxBytes) return text
        var offset = raw.size - maxBytes
        while (offset < raw.size && (raw[offset].toInt() and 0xC0) == 0x80) {
            offset += 1
        }
        return String(raw, offset, raw.size - offset, Charsets.UTF_8)
    }

    private fun truncateHeadUtf8(text: String, maxBytes: Int): String {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size <= maxBytes) return text
        var end = maxBytes
        while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) {
            end -= 1
        }
        return String(raw, 0, end, Charsets.UTF_8)
    }

    private fun collectNetworkStatus(lastState: NodeUiState): Pair<String?, String?> {
        val fallback = lastKnownStatusJson(lastState)
        if (!NodeBackend.isAvailable()) {
            return fallback to "Node backend is not available"
        }
        val diagnostics = runCatching {
            NodeBackend.queryNodeDiagnostics(WS_API_PORT).getOrThrow()
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
        val status = runCatching { NodeBackend.status().getOrThrow() }.getOrNull()
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

    private fun postGzip(context: Context, json: String): String {
        val headers = buildMap {
            put("User-Agent", "freenet-report")
            put("X-Install-Id", InstallId.get(context))
            // A service report uses its own token, never the crash token.
            BuildConfig.SERVICE_REPORT_TOKEN.trim().takeIf { it.isNotEmpty() }?.let { token ->
                put("Authorization", "Bearer $token")
            }
        }
        val body = HttpJson.postGzip(SERVICE_REPORT_SERVER, headers, json).getOrThrow()
        val code = JSONObject(body).optString("code").trim()
        require(code.isNotEmpty()) { "Upload succeeded but the server did not return a report code" }
        return code
    }
}

internal fun formatServiceReportLogs(): String? =
    NodeBackend.recentLogs(RING_LOG_ENTRIES)
        ?.let(::filterLogNoise)
        ?.let(::redactLogText)
        ?.ifBlank { null }
