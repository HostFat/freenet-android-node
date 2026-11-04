package org.freenet.androidnode

import android.content.Context

/**
 * Selects between the in-process JNI runtime and the experimental prebuilt
 * process. The choice is fixed at build time with -PprebuiltNode=true.
 */
internal object NodeBackend {
    private val prebuilt: Boolean
        get() = BuildConfig.PREBUILT_NODE

    fun startNetwork(context: Context, configJson: String): Result<String> =
        if (prebuilt) {
            PrebuiltNodeProcess.start(context, configJson, network = true)
        } else {
            NativeBridge.startNetworkNode(configJson)
        }

    fun startLocal(context: Context, configJson: String): Result<String> =
        if (prebuilt) {
            PrebuiltNodeProcess.start(context, configJson, network = false)
        } else {
            NativeBridge.startLocalNode(configJson)
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

    fun isActive(): Boolean = if (prebuilt) {
        PrebuiltNodeProcess.isRunning()
    } else {
        runCatching {
            val state = parseNodeStatus(NativeBridge.nodeStatus().getOrThrow()).state
            state != NodePhase.Stopped && state != NodePhase.Failed && state != NodePhase.Paused
        }.getOrDefault(false)
    }
}
