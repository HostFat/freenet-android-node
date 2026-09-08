package org.freenet.androidnode

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionLimitsTest {
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
    fun savePeerConnectionsOnlyWhenDraftDiffersFromSaved() {
        assertEquals(false, connectionLimitsAreDirty(10, 25, 10, 25))
        assertEquals(true, connectionLimitsAreDirty(11, 25, 10, 25))
        assertEquals(true, connectionLimitsAreDirty(10, 30, 10, 25))
        assertEquals(false, connectionLimitsAreDirty(50, 25, 25, 25))
    }
}
