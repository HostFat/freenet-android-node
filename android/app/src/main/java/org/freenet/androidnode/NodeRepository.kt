package org.freenet.androidnode

import android.content.Context
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import org.json.JSONObject

data class NodeUiState(
    val state: NodePhase = NodePhase.Stopped,
    val detail: String = "The foreground service is not running",
    val completedStartCycles: Long = 0,
    val serviceActive: Boolean = false,
    val mode: NodeMode = NodeMode.Local,
    val peers: Int = 0,
    val startedAtElapsedRealtimeMs: Long? = null,
    val lastLifecycleResponse: String = "No lifecycle action submitted",
    val taskRemovedWhileRunning: Boolean = false,
    val identityFingerprint: String? = null,
    val connectionAttempts: Long = 0,
    val successfulConnections: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val currentNetworkType: String = "Unavailable",
    val connectivityAvailable: Boolean = false,
    val networkMetered: Boolean = false,
    val vpnActive: Boolean = false,
    val lastNetworkError: String? = null,
    val highestSeenPeerVersion: String? = null,
    val udpPort: Int? = null,
    val natHint: String? = null,
    val udpPortInUse: Boolean = false,
) {
    val uptimeMs: Long
        get() = startedAtElapsedRealtimeMs
            ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0) }
            ?: 0
}

object NodeRepository {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(NodeUiState())

    val state: StateFlow<NodeUiState> = mutableState.asStateFlow()
    val policies: StateFlow<NodePolicyState> = NodePolicyRepository.state

    fun startLocal(context: Context) {
        val appContext = context.applicationContext
        NodePolicyRepository.stopAutomaticScheduling(appContext)
        mutableState.update {
            it.copy(
                state = NodePhase.Starting,
                detail = "Submitting the foreground-service start request",
                serviceActive = true,
                mode = NodeMode.Local,
                peers = 0,
                startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                lastLifecycleResponse = "Starting local node through NodeService",
                taskRemovedWhileRunning = false,
                udpPortInUse = false,
            )
        }
        runCatching { appContext.startForegroundService(NodeService.startLocalIntent(appContext)) }
            .onFailure { error -> publishServiceStartBlocked(error) }
    }

    fun startNetwork(context: Context) {
        val appContext = context.applicationContext
        NodePolicyRepository.initialize(appContext)
        NodePolicyRepository.setSuspended(appContext, false)
        mutableState.update {
            it.copy(
                state = NodePhase.Starting,
                detail = "Checking the selected power and network policies",
                serviceActive = true,
                mode = NodeMode.Network,
                peers = 0,
                startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                lastLifecycleResponse = "Starting network node through NodeService",
                taskRemovedWhileRunning = false,
                udpPortInUse = false,
            )
        }
        runCatching { appContext.startForegroundService(NodeService.startNetworkIntent(appContext)) }
            .onFailure { error -> publishServiceStartBlocked(error) }
    }

    private fun publishServiceStartBlocked(error: Throwable) {
        val detail = "Android did not allow the node service to start: " +
            (error.message ?: error.javaClass.simpleName)
        publishFailure(detail, "SERVICE_START_BLOCKED: $detail")
    }

    internal fun publishAutomaticStartBlocked(reason: String) {
        mutableState.update { NodeStateMachine.automaticStartBlocked(it, reason) }
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        NodePolicyRepository.initialize(appContext)
        if (NodePolicyRepository.state.value.power != NodePowerPolicy.Schedule) {
            NodePolicyRepository.stopAutomaticScheduling(appContext)
        }
        if (!mutableState.value.serviceActive) {
            // The native status call is blocking. Keep it off the caller thread.
            ioScope.launch {
                val response = NativeBridge.nodeStatus().getOrElse {
                    "JNI status error: ${it.message ?: "unknown error"}"
                }
                val status = parseNodeStatus(response)
                if (status.state == NodePhase.Stopped || status.state == NodePhase.Failed) {
                    publishStopped(status, response)
                    return@launch
                }
                appContext.startService(NodeService.stopIntent(appContext))
            }
            return
        }
        context.applicationContext.startService(NodeService.stopIntent(context.applicationContext))
    }

    fun pause(context: Context) {
        NodePolicyRepository.setSuspended(context.applicationContext, true)
        context.applicationContext.startService(NodeService.pauseIntent(context.applicationContext))
    }

    fun setPowerPolicy(context: Context, policy: NodePowerPolicy) {
        val appContext = context.applicationContext
        NodePolicyRepository.setPower(appContext, policy)
        if (policy == NodePowerPolicy.Manual) {
            if (mutableState.value.serviceActive) {
                appContext.startService(NodeService.reconcilePolicyIntent(appContext))
            }
        } else {
            appContext.startForegroundService(NodeService.reconcilePolicyIntent(appContext))
        }
    }

    fun setScheduleEveryHours(context: Context, hours: Int) {
        val appContext = context.applicationContext
        NodePolicyRepository.setScheduleEveryHours(appContext, hours)
        reconcileAutomatic(appContext)
    }

    fun setScheduleOnMinutes(context: Context, minutes: Int) {
        val appContext = context.applicationContext
        NodePolicyRepository.setScheduleOnMinutes(appContext, minutes)
        reconcileAutomatic(appContext)
    }

    private fun reconcileAutomatic(appContext: Context) {
        if (NodeRepository.state.value.serviceActive) {
            appContext.startService(NodeService.reconcilePolicyIntent(appContext))
        } else if (NodePolicyRepository.state.value.automatic) {
            appContext.startForegroundService(NodeService.reconcilePolicyIntent(appContext))
        }
    }

    fun setNetworkDataPolicy(context: Context, policy: NetworkDataPolicy) {
        val appContext = context.applicationContext
        NodePolicyRepository.setNetworkData(appContext, policy)
        if (mutableState.value.serviceActive) {
            appContext.startService(NodeService.reconcilePolicyIntent(appContext))
        } else if (NodePolicyRepository.state.value.automatic) {
            appContext.startForegroundService(NodeService.reconcilePolicyIntent(appContext))
        }
    }

    fun setMinConnections(context: Context, minConnections: Int) {
        NodePolicyRepository.setMinConnections(context.applicationContext, minConnections)
    }

    fun setMaxConnections(context: Context, maxConnections: Int) {
        NodePolicyRepository.setMaxConnections(context.applicationContext, maxConnections)
    }

    fun setConnectionLimits(context: Context, minConnections: Int, maxConnections: Int) {
        NodePolicyRepository.setConnectionLimits(
            context.applicationContext,
            minConnections,
            maxConnections,
        )
    }

    fun restartNetworkNode(context: Context) {
        val appContext = context.applicationContext
        NodePolicyRepository.initialize(appContext)
        NodePolicyRepository.setSuspended(appContext, false)
        mutableState.update {
            it.copy(
                state = NodePhase.Starting,
                detail = "Restarting the network node with the new connection limits",
                serviceActive = true,
                mode = NodeMode.Network,
                lastLifecycleResponse = "Restarting network node through NodeService",
                taskRemovedWhileRunning = false,
                udpPortInUse = false,
            )
        }
        appContext.startForegroundService(NodeService.restartNetworkIntent(appContext))
    }

    fun reportNotificationPermissionRequired() {
        mutableState.update {
            it.copy(
                detail = "Notification permission is required before starting the node",
                lastLifecycleResponse = "Start cancelled because notification permission was denied",
            )
        }
    }

    internal fun publishStarting(startedAtElapsedRealtimeMs: Long, mode: NodeMode) {
        mutableState.update {
            NodeStateMachine.starting(
                it,
                mode,
                "The foreground service is starting the native node",
                startedAtElapsedRealtimeMs,
            )
        }
    }

    internal fun publishWaiting(detail: String, paused: Boolean = false) {
        mutableState.update { NodeStateMachine.waiting(it, detail, paused) }
    }

    internal fun publishNativeStatus(
        response: String,
        serviceActive: Boolean,
        startedAtElapsedRealtimeMs: Long?,
    ): NodeUiState {
        val parsed = parseNodeStatus(response)
        return mutableState.updateAndGet {
            NodeStateMachine.nativeStatus(it, parsed, serviceActive, startedAtElapsedRealtimeMs)
        }
    }

    internal fun publishLifecycleResponse(response: String) {
        mutableState.update { it.copy(lastLifecycleResponse = response) }
    }

    internal fun publishPaused(status: NodeStatusSnapshot, response: String) {
        mutableState.update {
            NodeStateMachine.stopped(
                it,
                "The node was paused through a graceful native shutdown",
                response,
                completedStartCycles = status.completedStartCycles,
            ).copy(state = NodePhase.Paused)
        }
    }

    internal fun publishAfterReset() {
        mutableState.update {
            it.copy(
                state = NodePhase.Stopped,
                detail = "Node data was reset. Identity and config.toml were kept.",
                serviceActive = false,
                startedAtElapsedRealtimeMs = null,
                lastLifecycleResponse = "Node runtime data reset",
                lastNetworkError = null,
                udpPortInUse = false,
                natHint = null,
                peers = 0,
                bytesSent = 0,
                bytesReceived = 0,
                udpPort = null,
            )
        }
    }

    internal fun publishStopped(status: NodeStatusSnapshot, response: String) {
        mutableState.update {
            NodeStateMachine.stopped(
                it,
                status.detail,
                response,
                completedStartCycles = status.completedStartCycles,
            )
        }
    }

    internal fun publishPolicyStopped(
        status: NodeStatusSnapshot,
        response: String,
        reason: String,
    ) {
        mutableState.update {
            NodeStateMachine.stopped(
                it,
                "Network mode stopped safely: $reason",
                response,
                completedStartCycles = status.completedStartCycles,
                lastNetworkError = reason,
            )
        }
    }

    internal fun publishFailure(detail: String, response: String) {
        mutableState.update { NodeStateMachine.failed(it, detail, response) }
    }

    internal fun publishUdpPortInUse(detail: String, response: String) {
        mutableState.update {
            it.copy(
                state = NodePhase.Failed,
                detail = detail,
                serviceActive = false,
                startedAtElapsedRealtimeMs = null,
                lastLifecycleResponse = response,
                lastNetworkError = detail,
                udpPortInUse = true,
            )
        }
    }

    internal fun publishShutdownTimeout(detail: String, response: String) {
        mutableState.update {
            it.copy(
                state = NodePhase.Stopping,
                detail = detail,
                serviceActive = true,
                lastLifecycleResponse = response,
            )
        }
    }

    internal fun publishTaskRemoved() {
        mutableState.update {
            it.copy(
                detail = "The Activity task was removed; the foreground node continues running",
                taskRemovedWhileRunning = true,
            )
        }
    }

    internal fun publishUnexpectedServiceDestruction(status: NodeStatusSnapshot) {
        mutableState.update {
            it.copy(
                state = status.state,
                detail = "NodeService was destroyed and requested cooperative native shutdown",
                completedStartCycles = status.completedStartCycles,
                serviceActive = false,
                startedAtElapsedRealtimeMs = null,
            )
        }
    }

    internal fun publishNatHint(hint: String?) {
        mutableState.update { if (it.natHint == hint) it else it.copy(natHint = hint) }
    }

    internal fun publishConnectivity(snapshot: ConnectivitySnapshot, response: String) {
        mutableState.update {
            it.copy(
                currentNetworkType = snapshot.networkType,
                connectivityAvailable = snapshot.available,
                networkMetered = snapshot.metered,
                vpnActive = snapshot.vpn,
                lastLifecycleResponse = response,
            )
        }
    }
}

internal data class NodeStatusSnapshot(
    val state: NodePhase,
    val detail: String,
    val completedStartCycles: Long,
    val identityFingerprint: String?,
    val mode: NodeMode? = null,
    val peerCount: Int = 0,
    val connectionAttempts: Long = 0,
    val successfulConnections: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val currentNetworkType: String = "Unavailable",
    val connectivityAvailable: Boolean = false,
    val networkMetered: Boolean = false,
    val vpnActive: Boolean = false,
    val lastNetworkError: String? = null,
    val highestSeenPeerVersion: String? = null,
    val udpPort: Int? = null,
)

internal fun parseNodeStatus(response: String): NodeStatusSnapshot {
    return runCatching {
        val envelope = JSONObject(response)
        if (!envelope.optBoolean("ok")) {
            val error = envelope.optJSONObject("error")
            return@runCatching NodeStatusSnapshot(
                state = NodePhase.Failed,
                detail = error?.optString("message") ?: "Native status request failed",
                completedStartCycles = 0,
                identityFingerprint = null,
            )
        }
        val data = envelope.getJSONObject("data")
        NodeStatusSnapshot(
            state = NodePhase.fromWire(data.optString("state", "Unknown")),
            detail = data.optString("detail", "No detail"),
            completedStartCycles = data.optLong("completedStartCycles"),
            identityFingerprint = data.optionalString("identityFingerprint"),
            mode = NodeMode.fromWireOrNull(data.optionalString("mode")),
            peerCount = data.optInt("peerCount"),
            connectionAttempts = data.optLong("connectionAttempts"),
            successfulConnections = data.optLong("successfulConnections"),
            bytesSent = data.optLong("bytesSent"),
            bytesReceived = data.optLong("bytesReceived"),
            currentNetworkType = data.optString("currentNetworkType", "Unavailable"),
            connectivityAvailable = data.optBoolean("connectivityAvailable"),
            networkMetered = data.optBoolean("networkMetered"),
            vpnActive = data.optBoolean("vpnActive"),
            lastNetworkError = data.optionalString("lastNetworkError"),
            highestSeenPeerVersion = data.optionalString("highestSeenPeerVersion"),
            udpPort = data.optInt("udpPort").takeIf { data.has("udpPort") && !data.isNull("udpPort") && it > 0 },
        )
    }.getOrElse { error ->
        NodeStatusSnapshot(NodePhase.Failed, error.message ?: response, 0, null)
    }
}

private fun JSONObject.optionalString(name: String): String? =
    if (isNull(name)) null else optString(name)

internal fun androidNodeConfigJson(
    context: Context,
    connectivity: ConnectivitySnapshot? = null,
    networkDataPolicy: NetworkDataPolicy = NetworkDataPolicy.UnmeteredOnly,
    minConnections: Int = ConnectionLimits.DefaultMin,
    maxConnections: Int = ConnectionLimits.DefaultMax,
    udpPortMode: UdpPortMode = UdpPortMode.Saved,
    udpPort: Int = UdpPorts.Default,
): String {
    val persistentRoot = File(context.filesDir, "freenet")
    val config = JSONObject()
        .put("filesDir", context.filesDir.absolutePath)
        .put("cacheDir", context.cacheDir.absolutePath)
        .put("noBackupFilesDir", context.noBackupFilesDir.absolutePath)
        .put("stateDirectory", File(persistentRoot, "state").absolutePath)
        .put("databaseDirectory", File(persistentRoot, "database/local").absolutePath)
        .put("contractDirectory", File(persistentRoot, "contracts/local").absolutePath)
        .put(
            "configurationDirectory",
            File(context.filesDir, "freenet/config").absolutePath,
        )
        .put("logDirectory", File(context.filesDir, "freenet/logs").absolutePath)
        .put(
            "identityDirectory",
            File(context.noBackupFilesDir, "freenet/identity").absolutePath,
        )
        .put("temporaryDirectory", File(context.cacheDir, "freenet/temporary").absolutePath)
        .put("websocketPort", 7509)
        .put("minConnections", minConnections)
        .put("maxConnections", maxConnections)
        .put("udpPortMode", udpPortMode.name.lowercase())
        .put("udpPort", udpPort)
    if (connectivity != null) {
        config.put(
            "network",
            JSONObject()
                .put("allowMetered", networkDataPolicy == NetworkDataPolicy.AnyValidated)
                .put("connectivity", JSONObject(connectivity.toJson())),
        )
    }
    return config.toString()
}
