package org.freenet.androidnode

/** Native node lifecycle, parsed once at the JNI boundary. */
internal enum class NodePhase(val wire: String) {
    Starting("Starting"),
    RunningLocal("RunningLocal"),
    RunningNetwork("RunningNetwork"),
    Stopping("Stopping"),
    Stopped("Stopped"),
    Failed("Failed"),
    Paused("Paused"),
    Waiting("Waiting"),
    Unknown("Unknown"),
    ;

    val isLive: Boolean
        get() = this == Starting || this == RunningLocal || this == RunningNetwork || this == Stopping

    val isRunning: Boolean
        get() = this == RunningLocal || this == RunningNetwork

    companion object {
        fun fromWire(value: String?): NodePhase =
            entries.firstOrNull { it.wire == value?.trim() } ?: Unknown
    }
}

/** Whether the node keeps contracts local to the phone or joins Freenet. */
internal enum class NodeMode(val wire: String) {
    Local("Local"),
    Network("Network"),
    Unknown("Unknown"),
    ;

    companion object {
        fun fromWire(value: String?): NodeMode =
            entries.firstOrNull { it.wire == value?.trim() } ?: Unknown

        fun fromWireOrNull(value: String?): NodeMode? =
            entries.firstOrNull { it.wire == value?.trim() }
    }
}
