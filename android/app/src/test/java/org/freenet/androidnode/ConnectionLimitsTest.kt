package org.freenet.androidnode

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionLimitsTest {
    @Test
    fun panelVersionLineShowsAppAndNode() {
        assertEquals("App 0.2.134.10 · Node 0.2.134", panelVersionLine("0.2.134.10", "0.2.134"))
        assertEquals("App — · Node —", panelVersionLine(null, "  "))
        assertEquals(
            "0.2.134",
            coreVersionFromBuildInfo(
                "Freenet core 0.2.134; features: redb, trace; default gateway port: 31337",
            ),
        )
    }

    @Test
    fun changelogShowsOnlyAfterAnUpdateNotOnFirstInstall() {
        assertEquals(null, changelogLineIfUpdated("0.2.134.10", null))
        assertEquals(null, changelogLineIfUpdated("0.2.134.10", ""))
        assertEquals(null, changelogLineIfUpdated("0.2.134.10", "0.2.134.10"))
        assertEquals(AppChangelog.LINE, changelogLineIfUpdated("0.2.134.11", "0.2.134.10"))
        assertEquals(null, changelogLineIfUpdated(null, "0.2.134.10"))
    }

    @Test
    fun defaultsAreTenAndTwentyFive() {
        val state = NodePolicyState()
        assertEquals(10, state.minConnections)
        assertEquals(25, state.maxConnections)
        assertEquals(10, ConnectionLimits.DefaultMin)
        assertEquals(25, ConnectionLimits.DefaultMax)
    }

    @Test
    fun clampKeepsValuesInsideFreenetBoundsAndMinAtOrBelowMax() {
        assertEquals(1, ConnectionLimits.Floor)
        assertEquals(2000, ConnectionLimits.Ceiling)
        assertEquals(1, ConnectionLimits.coerce(0))
        assertEquals(2000, ConnectionLimits.coerce(2001))
        assertEquals(Pair(10, 25), ConnectionLimits.clampLoaded(10, 25))
        assertEquals(Pair(25, 25), ConnectionLimits.clampMin(50, 25))
        assertEquals(Pair(10, 10), ConnectionLimits.clampMax(25, 10))
        assertEquals(Pair(25, 25), ConnectionLimits.clampLoaded(50, 25))
        assertEquals(Pair(1, 2000), ConnectionLimits.clampLoaded(0, 5000))
    }

    @Test
    fun networkStatusLabelIsConnectedConnectingOrStopped() {
        assertEquals(
            "Stopped",
            networkStatusLabel("Stopped", "Network", 0, false),
        )
        assertEquals(
            "Connecting",
            networkStatusLabel("RunningNetwork", "Network", 0, true),
        )
        assertEquals(
            "Connected",
            networkStatusLabel("RunningNetwork", "Network", 4, true),
        )
        assertEquals(
            "Paused",
            networkStatusLabel("Paused", "Network", 0, true),
        )
        assertEquals(
            "Local",
            networkStatusLabel("RunningLocal", "Local", 0, true),
        )
    }

    @Test
    fun riverChatInviteOnlyWhenTheNetworkNodeIsRunning() {
        assertEquals(true, showRiverChatInvite("RunningNetwork", "Network"))
        assertEquals(false, showRiverChatInvite("Starting", "Network"))
        assertEquals(false, showRiverChatInvite("Paused", "Network"))
        assertEquals(false, showRiverChatInvite("Stopped", "Network"))
        assertEquals(false, showRiverChatInvite("RunningLocal", "Local"))
        assertEquals("https://freenet.org/quickstart", RIVER_CHAT_INVITE_URL)
    }

    @Test
    fun restartPromptOnlyWhenTheNetworkNodeIsLive() {
        assertEquals(true, networkNodeIsLive("RunningNetwork", "Network"))
        assertEquals(true, networkNodeIsLive("Starting", "Network"))
        assertEquals(true, networkNodeIsLive("Stopping", "Network"))
        assertEquals(false, networkNodeIsLive("RunningLocal", "Local"))
        assertEquals(false, networkNodeIsLive("Paused", "Network"))
        assertEquals(false, networkNodeIsLive("Stopped", "Network"))
    }

    @Test
    fun udpPortSettingsDirtyOnlyWhenModeOrCustomPortChanges() {
        assertEquals(
            false,
            udpPortSettingsAreDirty(UdpPortMode.Saved, 31337, UdpPortMode.Saved, 31337),
        )
        assertEquals(
            true,
            udpPortSettingsAreDirty(UdpPortMode.Random, 31337, UdpPortMode.Saved, 31337),
        )
        assertEquals(
            false,
            udpPortSettingsAreDirty(UdpPortMode.Random, 1, UdpPortMode.Random, 31337),
        )
        assertEquals(
            true,
            udpPortSettingsAreDirty(UdpPortMode.Custom, 55012, UdpPortMode.Custom, 31337),
        )
        assertEquals(1, UdpPorts.coerce(0))
        assertEquals(65535, UdpPorts.coerce(70000))
    }

    @Test
    fun savePeerConnectionsOnlyWhenDraftDiffersFromSaved() {
        assertEquals(false, connectionLimitsAreDirty(10, 25, 10, 25))
        assertEquals(true, connectionLimitsAreDirty(11, 25, 10, 25))
        assertEquals(true, connectionLimitsAreDirty(10, 30, 10, 25))
        assertEquals(false, connectionLimitsAreDirty(50, 25, 25, 25))
    }
}
