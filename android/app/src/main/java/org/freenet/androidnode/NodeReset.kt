package org.freenet.androidnode

import android.content.Context
import java.io.File

internal fun nodeRuntimeResetPaths(filesDir: File, cacheDir: File): List<File> =
    if (BuildConfig.PREBUILT_NODE) {
        listOf(
            File(filesDir, "freenet/prebuilt/data/contracts"),
            File(filesDir, "freenet/prebuilt/data/delegates"),
            File(filesDir, "freenet/prebuilt/data/db"),
            File(filesDir, "freenet/prebuilt/data/_EVENT_LOG"),
            File(cacheDir, "wasmtime"),
        )
    } else {
        listOf(
            File(filesDir, "freenet/state"),
            File(filesDir, "freenet/database"),
            File(filesDir, "freenet/contracts"),
            File(filesDir, "freenet/logs"),
            File(cacheDir, "freenet"),
        )
    }

internal fun canResetNode(serviceActive: Boolean, nativeState: NodePhase?): Boolean {
    if (serviceActive) return false
    if (nativeState != null && nodeIsLive(nativeState)) return false
    return true
}

internal object NodeReset {
    fun resetRuntimeData(context: Context): String {
        val app = context.applicationContext
        val nativeState = NativeBridge.nodeStatus().getOrNull()?.let { raw ->
            runCatching { parseNodeStatus(raw).state }.getOrNull()
        }
        if (!canResetNode(NodeRepository.state.value.serviceActive, nativeState)) {
            return "Stop the node before resetting it."
        }
        for (path in nodeRuntimeResetPaths(app.filesDir, app.cacheDir)) {
            if (path.exists()) {
                path.deleteRecursively()
            }
        }
        NodeRepository.publishAfterReset()
        return "Node data reset. Identity and config.toml were kept."
    }
}
