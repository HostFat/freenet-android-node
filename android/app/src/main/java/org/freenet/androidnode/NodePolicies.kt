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
    const val DefaultMax = 25

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
            .apply()
        mutableState.value = next
    }
}
