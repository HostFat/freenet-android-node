package org.freenet.androidnode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeStatusParseTest {
    @Test
    fun parsesRunningNetworkStatus() {
        val json = """
            {"ok":true,"data":{
              "state":"RunningNetwork",
              "detail":"connected",
              "completedStartCycles":3,
              "peerCount":7,
              "udpPort":55012,
              "mode":"Network",
              "bytesSent":120,
              "bytesReceived":340
            }}
        """.trimIndent()
        val parsed = parseNodeStatus(json)
        assertEquals(NodePhase.RunningNetwork, parsed.state)
        assertEquals("connected", parsed.detail)
        assertEquals(3L, parsed.completedStartCycles)
        assertEquals(7, parsed.peerCount)
        assertEquals(55012, parsed.udpPort)
        assertEquals(NodeMode.Network, parsed.mode)
        assertEquals(120L, parsed.bytesSent)
        assertEquals(340L, parsed.bytesReceived)
    }

    @Test
    fun errorEnvelopeBecomesFailure() {
        val parsed = parseNodeStatus("""{"ok":false,"error":{"message":"boom"}}""")
        assertEquals(NodePhase.Failed, parsed.state)
        assertEquals("boom", parsed.detail)
        assertNull(parsed.identityFingerprint)
    }

    @Test
    fun malformedJsonDoesNotThrow() {
        val parsed = parseNodeStatus("not json")
        assertEquals(NodePhase.Failed, parsed.state)
    }
}
