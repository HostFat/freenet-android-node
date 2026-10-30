package org.freenet.androidnode

import org.json.JSONObject

internal const val CRASH_INBOX_URL =
    "https://freenet-android-crash-inbox.hostfat.workers.dev/v1/crash"

internal fun presetCrashReportComment(appVersion: String): String =
    "This crash report was sent from the unofficial Android APK\n" +
        "$SERVICE_REPORT_REPO\n" +
        "App version: $appVersion\n"

internal fun buildCrashBody(detail: String, ring: String, hasFileLog: Boolean): String = buildString {
    append("Crash detail:\n")
    append(detail.ifBlank { "(none)" })
    append("\n\n")
    if (!hasFileLog) {
        append("No log files were on disk.\n")
    }
    if (ring.isNotBlank()) {
        append("\n--- ring captured at crash ---\n")
        append(ring)
    }
}

internal object CrashInbox {
    fun upload(context: android.content.Context, userMessage: String): Result<String> = runCatching {
        val token = BuildConfig.CRASH_REPORT_TOKEN.trim()
        require(token.isNotEmpty()) { "Crash reports are not configured in this build" }
        val detail = CrashReportOffer.detail(context)
        val ring = CrashReportOffer.ring(context)
        val hasFileLog = ServiceReport.hasLogFiles(context)
        if (!crashSnapshotIsUseful(detail, ring, hasFileLog)) {
            error("Nothing useful to send")
        }
        val notedAt = CrashReportOffer.notedAtEpochMs(context)
        val body = buildCrashBody(detail, ring, hasFileLog)
        val jsonObject = ServiceReport.buildReportJson(context, userMessage, extraLog = body)
        jsonObject.put(
            "network_status",
            org.json.JSONObject()
                .put("source", "crash-snapshot")
                .put("detail", detail)
                .put("notedAtEpochMs", notedAt)
                .toString(2),
        )
        jsonObject.put("network_status_error", org.json.JSONObject.NULL)
        val response = HttpJson.postGzip(
            CRASH_INBOX_URL,
            mapOf(
                "Authorization" to "Bearer $token",
                "X-App-Version" to appVersionName(context),
                "X-Install-Id" to InstallId.get(context),
            ),
            jsonObject.toString(),
        ).getOrThrow()
        val id = JSONObject(response).optString("id").trim()
        require(id.isNotEmpty()) { "Upload succeeded but the inbox did not return an id" }
        id
    }
}
