package org.freenet.androidnode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRedactionTest {
    @Test
    fun pathsAndLongTokensAreRedacted() {
        val token = "a".repeat(40)
        val config = """
            mode = "network"
            network-port = 31337
            config_dir = "/data/user/0/org.freenet.androidnode/files/freenet/config"
            transport_keypair = "/data/user/0/org.freenet.androidnode/no_backup/freenet/identity/transport"
            token = "$token"
            ws-api-port = 7509
        """.trimIndent()
        val redacted = redactConfigToml(config)
        assertFalse(redacted.contains("/data/user/0"))
        assertFalse(redacted.contains(token))
        assertTrue(redacted.contains("config_dir = \"<redacted>\""))
        assertTrue(redacted.contains("transport_keypair = \"<redacted>\""))
        assertTrue(redacted.contains("token = \"<redacted>\""))
        assertTrue(redacted.contains("network-port = 31337"))
        assertTrue(redacted.contains("mode = \"network\""))
    }

    @Test
    fun ordinaryValuesStayInPlace() {
        val config = "mode = \"local\"\nmin-number-of-connections = 10\ncipher = \"aes\"\n"
        assertEquals(config, redactConfigToml(config))
    }
}
