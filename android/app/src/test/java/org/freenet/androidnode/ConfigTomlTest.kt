package org.freenet.androidnode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigTomlTest {
    @Test
    fun upsertReplacesExistingKeyAndAppendsMissingKey() {
        val original = "min-number-of-connections = 10\nmax-number-of-connections = 25\n"
        val updated = ConfigToml.upsertInt(original, ConfigToml.MIN_KEY, 15)
        assertEquals(15, ConfigToml.parseInt(updated, ConfigToml.MIN_KEY))
        assertEquals(25, ConfigToml.parseInt(updated, ConfigToml.MAX_KEY))

        val appended = ConfigToml.upsertInt("mode = \"network\"\n", ConfigToml.MAX_KEY, 200)
        assertEquals(200, ConfigToml.parseInt(appended, ConfigToml.MAX_KEY))
    }

    @Test
    fun parseIgnoresMissingKeys() {
        assertNull(ConfigToml.parseInt("bandwidth-limit = 1\n", ConfigToml.MIN_KEY))
        assertEquals(10, ConfigToml.parseInt("  min-number-of-connections = 10\n", ConfigToml.MIN_KEY))
    }

    @Test
    fun fingerprintChangesWhenTextChanges() {
        val first = ConfigToml.fingerprint("min-number-of-connections = 10\n")
        val second = ConfigToml.fingerprint("min-number-of-connections = 11\n")
        assertEquals(false, first == second)
        assertEquals(first, ConfigToml.fingerprint("min-number-of-connections = 10\n"))
    }

    @Test
    fun restartPromptIgnoresNodeRewriteOfConfigFile() {
        assertEquals(
            false,
            shouldPromptRestartForConfigFile(
                fingerprintChanged = true,
                userInitiatedEdit = false,
                networkLive = true,
            ),
        )
    }

    @Test
    fun restartPromptAfterUserEditWhileNetworkNodeIsLive() {
        assertEquals(
            true,
            shouldPromptRestartForConfigFile(
                fingerprintChanged = true,
                userInitiatedEdit = true,
                networkLive = true,
            ),
        )
        assertEquals(
            false,
            shouldPromptRestartForConfigFile(
                fingerprintChanged = true,
                userInitiatedEdit = true,
                networkLive = false,
            ),
        )
        assertEquals(
            false,
            shouldPromptRestartForConfigFile(
                fingerprintChanged = false,
                userInitiatedEdit = true,
                networkLive = true,
            ),
        )
    }
}
