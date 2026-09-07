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
    fun clampKeepsMinAtOrBelowMaxInsideThePhoneRange() {
        assertEquals(Pair(10, 25), ConnectionLimits.clampPair(10, 25))
        assertEquals(Pair(20, 20), ConnectionLimits.clampPair(20, 15))
        assertEquals(Pair(10, 10), ConnectionLimits.clampPair(1, 5))
        assertEquals(Pair(25, 25), ConnectionLimits.clampPair(40, 80))
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
}
