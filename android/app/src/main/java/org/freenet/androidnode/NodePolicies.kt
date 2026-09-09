package org.freenet.androidnode

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NodePowerPolicy(val displayName: String, val shortLabel: String) {
    Manual("Manual", "Manual"),
    Charging("Charging", "Charging"),
    Always("Always (best effort)", "Always"),
}

enum class NetworkDataPolicy(val displayName: String, val shortLabel: String) {
    UnmeteredOnly("Unmetered only", "Unmetered"),
    AnyValidated("Any validated network", "Any validated"),
}

enum class UdpPortMode(val displayName: String, val shortLabel: String) {
    Saved("Saved", "Saved"),
    Custom("Custom", "Custom"),
    Random("Random", "Random"),
}

internal object UdpPorts {
    const val Floor = 1
    const val Ceiling = 65535
    const val Default = 31337

    fun coerce(value: Int): Int = value.coerceIn(Floor, Ceiling)
}

internal object ConnectionLimits {
    const val Floor = 1
    const val Ceiling = 2000
    const val DefaultMin = 10
    const val DefaultMax = 10

    fun coerce(value: Int): Int = value.coerceIn(Floor, Ceiling)

    fun clampLoaded(min: Int, max: Int): Pair<Int, Int> {
        val lo = coerce(min)
        val hi = coerce(max)
        return if (lo <= hi) lo to hi else hi to hi
    }

    fun clampMin(min: Int, max: Int): Pair<Int, Int> {
        val lo = coerce(min)
        val hi = coerce(max)
        return if (lo <= hi) lo to hi else hi to hi
    }

    fun clampMax(min: Int, max: Int): Pair<Int, Int> {
        val lo = coerce(min)
        val hi = coerce(max)
        return if (lo <= hi) lo to hi else hi to hi
    }
}

internal fun networkNodeIsLive(state: String, mode: String): Boolean =
    mode == "Network" && state in setOf("Starting", "RunningNetwork", "Stopping")

internal fun nodeIsLive(state: String): Boolean =
    state in setOf("Starting", "RunningNetwork", "RunningLocal", "Stopping")

internal const val RIVER_CHAT_INVITE_URL = "https://freenet.org/quickstart"

internal fun showRiverChatInvite(state: String, mode: String): Boolean =
    mode == "Network" && state == "RunningNetwork"

internal fun connectionLimitsAreDirty(
    draftMin: Int,
    draftMax: Int,
    savedMin: Int,
    savedMax: Int,
): Boolean {
    val (min, max) = ConnectionLimits.clampLoaded(draftMin, draftMax)
    return min != savedMin || max != savedMax
}

internal fun isGatewayOnlyHint(natHint: String?): Boolean =
    natHint?.contains("gateways", ignoreCase = true) == true

internal fun networkStatusLabel(
    state: String,
    mode: String,
    peers: Int,
    serviceActive: Boolean,
    natHint: String? = null,
): String =
    when {
        !serviceActive || state == "Stopped" || state == "Failed" -> "Stopped"
        state == "Paused" -> "Paused"
        state == "Waiting" -> "Waiting"
        mode == "Local" && (state == "RunningLocal" || state == "Starting") ->
            if (state == "Starting") "Starting local" else "Local"
        state == "Starting" || state == "Stopping" -> "Connecting"
        state == "RunningNetwork" && peers > 0 -> "Connected"
        state == "RunningNetwork" && isGatewayOnlyHint(natHint) -> "Gateway only"
        state == "RunningNetwork" -> "Connecting"
        else -> state
    }

internal fun formatTrafficBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(java.util.Locale.US, kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(java.util.Locale.US, mb)
    return "%.2f GB".format(java.util.Locale.US, mb / 1024.0)
}

internal fun formatLastUp(nowMs: Long, lastUpEpochMs: Long): String {
    if (lastUpEpochMs <= 0L) return "never"
    if (nowMs - lastUpEpochMs < 60_000L) return "just now"
    return java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM,
        java.text.DateFormat.SHORT,
    ).format(java.util.Date(lastUpEpochMs))
}

internal const val NATIVE_ERROR_UDP_PORT_IN_USE = "UDP_PORT_IN_USE"

internal fun nativeErrorCode(response: String): String? {
    val fromJson = runCatching {
        org.json.JSONObject(response).optJSONObject("error")?.optString("code")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
    if (fromJson != null) return fromJson
    return Regex("\"code\"\\s*:\\s*\"([A-Z0-9_]+)\"").find(response)?.groupValues?.get(1)
}

internal fun isUdpPortInUseResponse(response: String): Boolean =
    nativeErrorCode(response) == NATIVE_ERROR_UDP_PORT_IN_USE

internal fun identityRestoreSucceeded(message: String): Boolean =
    message.startsWith("Identity restored")

internal fun udpPortSettingsAreDirty(
    draftMode: UdpPortMode,
    draftPort: Int,
    savedMode: UdpPortMode,
    savedPort: Int,
): Boolean {
    if (draftMode != savedMode) return true
    return draftMode == UdpPortMode.Custom && UdpPorts.coerce(draftPort) != savedPort
}

data class NodePolicyState(
    val power: NodePowerPolicy = NodePowerPolicy.Manual,
    val networkData: NetworkDataPolicy = NetworkDataPolicy.UnmeteredOnly,
    val suspendedByUser: Boolean = false,
    val minConnections: Int = ConnectionLimits.DefaultMin,
    val maxConnections: Int = ConnectionLimits.DefaultMax,
    val udpPortMode: UdpPortMode = UdpPortMode.Saved,
    val udpPort: Int = UdpPorts.Default,
    val startOnBoot: Boolean = false,
    val autoRestartOnCrash: Boolean = false,
    val notifyConnected: Boolean = false,
    val notifyStopped: Boolean = false,
    val notifyUdpBusy: Boolean = true,
    val notifyUpdate: Boolean = true,
    val lastNetworkUpEpochMs: Long = 0L,
) {
    val automatic: Boolean
        get() = power != NodePowerPolicy.Manual

    fun powerEligible(charging: Boolean): Boolean = when (power) {
        NodePowerPolicy.Manual -> true
        NodePowerPolicy.Charging -> charging
        NodePowerPolicy.Always -> true
    }

    internal fun networkEligible(connectivity: ConnectivitySnapshot): Boolean =
        connectivity.available &&
            connectivity.validated &&
            (networkData == NetworkDataPolicy.AnyValidated || !connectivity.metered)
}

object NodePolicyRepository {
    private const val PREFERENCES_NAME = "node_policies"
    private const val POWER_KEY = "power_policy"
    private const val NETWORK_DATA_KEY = "network_data_policy"
    private const val SUSPENDED_KEY = "suspended_by_user"
    private const val MIN_CONNECTIONS_KEY = "min_connections"
    private const val MAX_CONNECTIONS_KEY = "max_connections"
    private const val UDP_PORT_MODE_KEY = "udp_port_mode"
    private const val UDP_PORT_KEY = "udp_port"
    private const val START_ON_BOOT_KEY = "start_on_boot"
    private const val AUTO_RESTART_ON_CRASH_KEY = "auto_restart_on_crash"
    private const val NOTIFY_CONNECTED_KEY = "notify_connected"
    private const val NOTIFY_STOPPED_KEY = "notify_stopped"
    private const val NOTIFY_UDP_BUSY_KEY = "notify_udp_busy"
    private const val NOTIFY_UPDATE_KEY = "notify_update"
    private const val LAST_NETWORK_UP_KEY = "last_network_up_ms"

    private val mutableState = MutableStateFlow(NodePolicyState())
    val state: StateFlow<NodePolicyState> = mutableState.asStateFlow()

    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val (minConnections, maxConnections) = ConnectionLimits.clampLoaded(
            preferences.getInt(MIN_CONNECTIONS_KEY, ConnectionLimits.DefaultMin),
            preferences.getInt(MAX_CONNECTIONS_KEY, ConnectionLimits.DefaultMax),
        )
        mutableState.value = NodePolicyState(
            power = preferences.getString(POWER_KEY, null)
                ?.let { stored -> enumValues<NodePowerPolicy>().find { it.name == stored } }
                ?: NodePowerPolicy.Manual,
            networkData = preferences.getString(NETWORK_DATA_KEY, null)
                ?.let { stored -> enumValues<NetworkDataPolicy>().find { it.name == stored } }
                ?: NetworkDataPolicy.UnmeteredOnly,
            suspendedByUser = preferences.getBoolean(SUSPENDED_KEY, false),
            minConnections = minConnections,
            maxConnections = maxConnections,
            udpPortMode = preferences.getString(UDP_PORT_MODE_KEY, null)
                ?.let { stored -> enumValues<UdpPortMode>().find { it.name == stored } }
                ?: UdpPortMode.Saved,
            udpPort = UdpPorts.coerce(
                preferences.getInt(UDP_PORT_KEY, UdpPorts.Default),
            ),
            startOnBoot = preferences.getBoolean(START_ON_BOOT_KEY, false),
            autoRestartOnCrash = preferences.getBoolean(AUTO_RESTART_ON_CRASH_KEY, false),
            notifyConnected = preferences.getBoolean(NOTIFY_CONNECTED_KEY, false),
            notifyStopped = preferences.getBoolean(NOTIFY_STOPPED_KEY, false),
            notifyUdpBusy = preferences.getBoolean(NOTIFY_UDP_BUSY_KEY, true),
            notifyUpdate = preferences.getBoolean(NOTIFY_UPDATE_KEY, true),
            lastNetworkUpEpochMs = preferences.getLong(LAST_NETWORK_UP_KEY, 0L),
        )
        initialized = true
    }

    fun setPower(context: Context, power: NodePowerPolicy) {
        initialize(context)
        persist(context, mutableState.value.copy(power = power, suspendedByUser = false))
    }

    fun setNetworkData(context: Context, networkData: NetworkDataPolicy) {
        initialize(context)
        persist(context, mutableState.value.copy(networkData = networkData))
    }

    fun setMinConnections(context: Context, minConnections: Int) {
        initialize(context)
        val current = mutableState.value
        val (min, max) = ConnectionLimits.clampMin(minConnections, current.maxConnections)
        persist(context, current.copy(minConnections = min, maxConnections = max))
    }

    fun setMaxConnections(context: Context, maxConnections: Int) {
        initialize(context)
        val current = mutableState.value
        val (min, max) = ConnectionLimits.clampMax(current.minConnections, maxConnections)
        persist(context, current.copy(minConnections = min, maxConnections = max))
    }

    fun setConnectionLimits(context: Context, minConnections: Int, maxConnections: Int) {
        initialize(context)
        val (min, max) = ConnectionLimits.clampLoaded(minConnections, maxConnections)
        persist(context, mutableState.value.copy(minConnections = min, maxConnections = max))
    }

    fun setStartOnBoot(context: Context, enabled: Boolean) {
        initialize(context)
        persist(context, mutableState.value.copy(startOnBoot = enabled))
    }

    fun setAutoRestartOnCrash(context: Context, enabled: Boolean) {
        initialize(context)
        persist(context, mutableState.value.copy(autoRestartOnCrash = enabled))
    }

    fun setNotifyConnected(context: Context, enabled: Boolean) {
        initialize(context)
        persist(context, mutableState.value.copy(notifyConnected = enabled))
    }

    fun setNotifyStopped(context: Context, enabled: Boolean) {
        initialize(context)
        persist(context, mutableState.value.copy(notifyStopped = enabled))
    }

    fun setNotifyUdpBusy(context: Context, enabled: Boolean) {
        initialize(context)
        persist(context, mutableState.value.copy(notifyUdpBusy = enabled))
    }

    fun setNotifyUpdate(context: Context, enabled: Boolean) {
        initialize(context)
        persist(context, mutableState.value.copy(notifyUpdate = enabled))
    }

    fun recordNetworkUp(context: Context) {
        initialize(context)
        val now = System.currentTimeMillis()
        val current = mutableState.value
        if (now - current.lastNetworkUpEpochMs < LAST_UP_WRITE_INTERVAL_MS) return
        persist(context, current.copy(lastNetworkUpEpochMs = now))
    }

    fun setUdpPortSettings(context: Context, mode: UdpPortMode, port: Int) {
        initialize(context)
        persist(
            context,
            mutableState.value.copy(
                udpPortMode = mode,
                udpPort = UdpPorts.coerce(port),
            ),
        )
    }

    fun setSuspended(context: Context, suspended: Boolean) {
        initialize(context)
        persist(context, mutableState.value.copy(suspendedByUser = suspended))
    }

    fun stopAutomaticScheduling(context: Context) {
        initialize(context)
        persist(
            context,
            mutableState.value.copy(
                power = NodePowerPolicy.Manual,
                suspendedByUser = false,
            ),
        )
    }

    private fun persist(context: Context, next: NodePolicyState) {
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(POWER_KEY, next.power.name)
            .putString(NETWORK_DATA_KEY, next.networkData.name)
            .putBoolean(SUSPENDED_KEY, next.suspendedByUser)
            .putInt(MIN_CONNECTIONS_KEY, next.minConnections)
            .putInt(MAX_CONNECTIONS_KEY, next.maxConnections)
            .putString(UDP_PORT_MODE_KEY, next.udpPortMode.name)
            .putInt(UDP_PORT_KEY, next.udpPort)
            .putBoolean(START_ON_BOOT_KEY, next.startOnBoot)
            .putBoolean(AUTO_RESTART_ON_CRASH_KEY, next.autoRestartOnCrash)
            .putBoolean(NOTIFY_CONNECTED_KEY, next.notifyConnected)
            .putBoolean(NOTIFY_STOPPED_KEY, next.notifyStopped)
            .putBoolean(NOTIFY_UDP_BUSY_KEY, next.notifyUdpBusy)
            .putBoolean(NOTIFY_UPDATE_KEY, next.notifyUpdate)
            .putLong(LAST_NETWORK_UP_KEY, next.lastNetworkUpEpochMs)
            .apply()
        mutableState.value = next
    }

    private const val LAST_UP_WRITE_INTERVAL_MS = 60_000L
}
