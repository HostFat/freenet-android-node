package org.freenet.androidnode

/**
 * Pure transitions for the UI state. Keeping them here makes the lifecycle
 * testable without Android services or JNI.
 */
internal object NodeStateMachine {
    fun starting(
        current: NodeUiState,
        mode: NodeMode,
        detail: String,
        startedAtElapsedRealtimeMs: Long,
    ): NodeUiState = current.copy(
        state = NodePhase.Starting,
        detail = detail,
        serviceActive = true,
        mode = mode,
        peers = 0,
        startedAtElapsedRealtimeMs = startedAtElapsedRealtimeMs,
        taskRemovedWhileRunning = false,
        udpPortInUse = false,
    )

    fun waiting(current: NodeUiState, detail: String, paused: Boolean): NodeUiState = current.copy(
        state = if (paused) NodePhase.Paused else NodePhase.Waiting,
        detail = detail,
        serviceActive = true,
        mode = NodeMode.Network,
        peers = 0,
        startedAtElapsedRealtimeMs = null,
        lastLifecycleResponse = detail,
        lastNetworkError = null,
    )

    fun stopped(
        current: NodeUiState,
        detail: String,
        response: String,
        completedStartCycles: Long = current.completedStartCycles,
        lastNetworkError: String? = current.lastNetworkError,
    ): NodeUiState = current.copy(
        state = NodePhase.Stopped,
        detail = detail,
        completedStartCycles = completedStartCycles,
        serviceActive = false,
        startedAtElapsedRealtimeMs = null,
        lastLifecycleResponse = response,
        lastNetworkError = lastNetworkError,
    )

    fun automaticStartBlocked(current: NodeUiState, detail: String): NodeUiState = current.copy(
        state = NodePhase.Waiting,
        detail = detail,
        serviceActive = false,
        startedAtElapsedRealtimeMs = null,
        lastLifecycleResponse = detail,
    )

    fun failed(current: NodeUiState, detail: String, response: String): NodeUiState = current.copy(
        state = NodePhase.Failed,
        detail = detail,
        serviceActive = false,
        startedAtElapsedRealtimeMs = null,
        lastLifecycleResponse = response,
        lastNetworkError = detail,
        udpPortInUse = false,
    )

    fun nativeStatus(
        current: NodeUiState,
        parsed: NodeStatusSnapshot,
        serviceActive: Boolean,
        startedAtElapsedRealtimeMs: Long?,
    ): NodeUiState = current.copy(
        state = parsed.state,
        detail = parsed.detail,
        completedStartCycles = parsed.completedStartCycles,
        serviceActive = serviceActive,
        mode = parsed.mode ?: current.mode,
        peers = parsed.peerCount,
        startedAtElapsedRealtimeMs = startedAtElapsedRealtimeMs,
        identityFingerprint = parsed.identityFingerprint,
        connectionAttempts = parsed.connectionAttempts,
        successfulConnections = parsed.successfulConnections,
        bytesSent = parsed.bytesSent,
        bytesReceived = parsed.bytesReceived,
        currentNetworkType = parsed.currentNetworkType,
        connectivityAvailable = parsed.connectivityAvailable,
        networkMetered = parsed.networkMetered,
        vpnActive = parsed.vpnActive,
        lastNetworkError = parsed.lastNetworkError,
        highestSeenPeerVersion = parsed.highestSeenPeerVersion,
        udpPort = parsed.udpPort,
        udpPortInUse = false,
    )
}
