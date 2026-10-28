package org.freenet.androidnode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeStateMachineTest {
    @Test
    fun startingUsesTheRequestedMode() {
        val next = NodeStateMachine.starting(NodeUiState(), NodeMode.Network, "starting", 42L)
        assertEquals(NodePhase.Starting, next.state)
        assertEquals(NodeMode.Network, next.mode)
        assertEquals(42L, next.startedAtElapsedRealtimeMs)
        assertTrue(next.serviceActive)
    }

    @Test
    fun waitingCanBePaused() {
        val waiting = NodeStateMachine.waiting(NodeUiState(), "waiting for network", paused = false)
        assertEquals(NodePhase.Waiting, waiting.state)
        val paused = NodeStateMachine.waiting(waiting, "paused", paused = true)
        assertEquals(NodePhase.Paused, paused.state)
    }

    @Test
    fun stoppingClearsTheServiceFlag() {
        val running = NodeStateMachine.starting(NodeUiState(), NodeMode.Network, "started", 1L)
        val stopped = NodeStateMachine.stopped(running, "stopped", "ok")
        assertEquals(NodePhase.Stopped, stopped.state)
        assertFalse(stopped.serviceActive)
        assertEquals(null, stopped.startedAtElapsedRealtimeMs)
    }

    @Test
    fun failureKeepsTheReason() {
        val failed = NodeStateMachine.failed(NodeUiState(), "JNI failed", "error")
        assertEquals(NodePhase.Failed, failed.state)
        assertEquals("JNI failed", failed.lastNetworkError)
    }

    @Test
    fun nativeStatusCopiesPeersAndMode() {
        val parsed = NodeStatusSnapshot(
            state = NodePhase.RunningNetwork,
            detail = "running",
            completedStartCycles = 2,
            identityFingerprint = "abc",
            mode = NodeMode.Network,
            peerCount = 7,
        )
        val next = NodeStateMachine.nativeStatus(NodeUiState(), parsed, true, 9L)
        assertEquals(NodePhase.RunningNetwork, next.state)
        assertEquals(NodeMode.Network, next.mode)
        assertEquals(7, next.peers)
    }

    @Test
    fun automaticStartBlockedWaitsWithoutAService() {
        val blocked = NodeStateMachine.automaticStartBlocked(NodeUiState(), "Android refused")
        assertEquals(NodePhase.Waiting, blocked.state)
        assertFalse(blocked.serviceActive)
    }
}
