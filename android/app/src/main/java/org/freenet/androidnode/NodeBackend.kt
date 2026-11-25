package org.freenet.androidnode

import android.content.Context
import org.json.JSONObject

/**
 * Unified facade over the two node runtimes (in-process JNI adapter vs prebuilt
 * `libfreenet.so` child process). Selected at build time via `-PprebuiltNode=true`.
 */
internal object NodeBackend {
    private val prebuilt: Boolean
        get() = BuildConfig.PREBUILT_NODE

    fun startNetwork(context: Context, configJson: String): Result<String> =
        if (prebuilt) {
            PrebuiltNodeProcess.start(context, configJson)
        } else {
            NativeBridge.startNetworkNode(configJson)
        }

    fun stop(): Result<String> =
        if (prebuilt) PrebuiltNodeProcess.stop() else NativeBridge.stopNode()

    fun status(): Result<String> =
        if (prebuilt) PrebuiltNodeProcess.status() else NativeBridge.nodeStatus()

    fun updateConnectivity(json: String): Result<String> =
        if (prebuilt) {
            PrebuiltNodeProcess.updateConnectivity(json)
        } else {
            NativeBridge.updateConnectivity(json)
        }

    fun appendInfoLog(detail: String) {
        if (prebuilt) PrebuiltNodeProcess.appendInfoLog(detail) else NativeBridge.appendInfoLog(detail)
    }

    fun recentLogs(limit: Int): String? =
        if (prebuilt) {
            PrebuiltNodeProcess.recentLogs(limit).takeIf {
                it.isNotBlank() && it != "No process output yet."
            }
        } else {
            val raw = NativeBridge.recentLogs(limit).getOrNull() ?: return null
            runCatching {
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

    fun recentLogsRaw(limit: Int): Result<String> =
        if (prebuilt) {
            Result.success(PrebuiltNodeProcess.recentLogs(limit))
        } else {
            NativeBridge.recentLogs(limit)
        }

    fun coreVersion(): String? =
        if (prebuilt) {
            BuildConfig.CORE_VERSION.takeIf { it.isNotBlank() && it != "unknown" }
        } else {
            coreVersionFromBuildInfo(NativeBridge.freenetBuildInfo().getOrNull())
        }

    fun buildInfo(): Result<String> =
        if (prebuilt) {
            Result.success("Prebuilt node backend (core ${BuildConfig.CORE_VERSION})")
        } else {
            NativeBridge.buildInfo()
        }

    fun freenetBuildInfo(): Result<String> =
        if (prebuilt) {
            Result.success("Freenet core ${BuildConfig.CORE_VERSION} (prebuilt binary)")
        } else {
            NativeBridge.freenetBuildInfo()
        }

    fun queryNodeDiagnostics(websocketPort: Int): Result<String> =
        if (prebuilt) {
            runCatching {
                val counts = DashboardStatus.read()
                    ?: error("Local dashboard on 127.0.0.1:$websocketPort is not reachable")
                val data = JSONObject()
                    .put("source", "prebuilt-dashboard")
                    .put("connections", counts.connections)
                    .put("peers", counts.peers)
                    .put("bytesSent", counts.bytesSent)
                    .put("bytesReceived", counts.bytesReceived)
                    .put("uploadKbps", counts.uploadKbps)
                    .put("downloadKbps", counts.downloadKbps)
                    .put("natHint", counts.natHint ?: JSONObject.NULL)
                JSONObject().put("ok", true).put("data", data).toString()
            }
        } else {
            NativeBridge.queryNodeDiagnostics(websocketPort)
        }

    fun validateConfig(configText: String): Result<String>? =
        if (!prebuilt && NativeBridge.isLoaded) {
            NativeBridge.validateConfig(configText)
        } else {
            null
        }

    fun killOrphan(context: Context) {
        if (prebuilt) {
            PrebuiltNodeProcess.killOrphan(context)
        }
    }

    fun isAvailable(): Boolean = if (prebuilt) true else NativeBridge.isLoaded

    fun isActive(): Boolean = if (prebuilt) {
        PrebuiltNodeProcess.isRunning()
    } else {
        runCatching {
            val state = parseNodeStatus(NativeBridge.nodeStatus().getOrThrow()).state
            state != NodePhase.Stopped && state != NodePhase.Failed && state != NodePhase.Paused
        }.getOrDefault(false)
    }
}
