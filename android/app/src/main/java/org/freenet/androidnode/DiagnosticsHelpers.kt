package org.freenet.androidnode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import org.json.JSONObject

private const val DIAGNOSTIC_LOG_ENTRIES = 128

internal fun formatRecentLogs(): String {
    return NodeBackend.recentLogs(DIAGNOSTIC_LOG_ENTRIES) ?: "No log entries yet."
}

internal fun diagnosticSnapshot(): String {
    fun resultOrError(result: Result<String>): Any = result.fold(
        onSuccess = { value ->
            runCatching { JSONObject(value) }.fold(
                onSuccess = { it },
                onFailure = { value },
            )
        },
        onFailure = { error -> JSONObject().put("error", error.message ?: "unknown backend error") },
    )

    return JSONObject()
        .put("capturedAtEpochMs", System.currentTimeMillis())
        .put("backend", if (BuildConfig.PREBUILT_NODE) "prebuilt" else "jni")
        .put("nodeStatus", resultOrError(NodeBackend.status()))
        .put("recentLogs", resultOrError(NodeBackend.recentLogsRaw(DIAGNOSTIC_LOG_ENTRIES)))
        .put("androidAdapter", resultOrError(NodeBackend.buildInfo()))
        .put("freenetCore", resultOrError(NodeBackend.freenetBuildInfo()))
        .toString(2)
}

internal fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Freenet diagnostics", value))
}

internal fun writeTextToUri(context: Context, uri: Uri, text: String): String {
    return runCatching {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
        } ?: return context.getString(R.string.export_logs_failed)
        context.getString(R.string.export_logs_saved)
    }.getOrElse { context.getString(R.string.export_logs_failed) }
}
