package org.freenet.androidnode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import javax.crypto.AEADBadTagException

class AuditFixesTest {
    @Test
    fun fallbackTomlValidatorHandlesCommentsEscapesAndQuotes() {
        val valid = """
            # Comment with unbalanced [ and "quote and {brace
            mode = "network"
            note = "escaped \"quote\" inside"
            literal = 'single "quoted" [literal]'
            arr = [1, 2, 3]
        """.trimIndent()
        assertNull(ConfigToml.validationError(valid))
        assertEquals(
            "unterminated string",
            ConfigToml.fallbackValidationError("mode = \"network\n"),
        )
        assertEquals(
            "unbalanced brackets",
            ConfigToml.fallbackValidationError("mode = \"network\"\narr = [1, 2\n"),
        )
    }

    @Test
    fun identityBackupEncryptionRoundTripAndWrongPassphrase() {
        val payload = "PK\u0003\u0004dummy-zip-payload".toByteArray(Charsets.UTF_8)
        val encrypted = IdentityBackup.encryptBackupPayload(payload, "correct-horse".toCharArray())
        assertTrue(IdentityBackup.isEncryptedHeader(encrypted))
        val decrypted = IdentityBackup.decryptBackupPayload(encrypted, "correct-horse".toCharArray())
        assertArrayEquals(payload, decrypted)

        var failed = false
        try {
            IdentityBackup.decryptBackupPayload(encrypted, "wrong-pass".toCharArray())
        } catch (_: AEADBadTagException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun identityBackupAllowlistRejectsTraversalAndDotfiles() {
        assertTrue(IdentityBackup.isAllowedIdentityFileName("transport_keypair", prebuilt = true))
        assertTrue(IdentityBackup.isAllowedIdentityFileName("transport_keypair", prebuilt = false))
        assertTrue(IdentityBackup.isAllowedIdentityFileName("delegate_cipher", prebuilt = false))
        assertFalse(IdentityBackup.isAllowedIdentityFileName("evil.sh", prebuilt = false))
        assertFalse(IdentityBackup.isAllowedIdentityFileName("..", prebuilt = true))
        assertFalse(IdentityBackup.isAllowedIdentityFileName(".", prebuilt = true))
        assertFalse(IdentityBackup.isAllowedIdentityFileName(".hidden", prebuilt = true))
        assertFalse(IdentityBackup.isAllowedIdentityFileName("a/b", prebuilt = true))
    }

    @Test
    fun prebuiltHostFormattingBracketsIpv6() {
        assertEquals("1.2.3.4:31337", PrebuiltNodeProcess.formatResolvedHostPort("1.2.3.4", "31337"))
        assertEquals(
            "[2001:db8::1]:31337",
            PrebuiltNodeProcess.formatResolvedHostPort("2001:db8::1", "31337"),
        )
        assertTrue(PrebuiltNodeProcess.isFreenetProcessCmdline("/data/app/.../lib/arm64/libfreenet.so network"))
        assertFalse(PrebuiltNodeProcess.isFreenetProcessCmdline("org.freenet.androidnode"))
    }

    @Test
    fun updateInstallerSha256ParserRequiresExactApkName() {
        val goodHash = "a".repeat(64)
        val decoyHash = "b".repeat(64)
        val sums = """
            $decoyHash  evil-freenet-android-node-release.apk
            $goodHash  apk/freenet-android-node-release.apk
        """.trimIndent()
        assertEquals(goodHash, UpdateInstaller.parseExpectedSha256(sums))
    }

    @Test
    fun siteActivityDecodesJsJsonStringTitles() {
        assertNull(SiteActivity.decodeJsStringResult("null"))
        assertNull(SiteActivity.decodeJsStringResult("undefined"))
        assertEquals("River \"Chat\" \\ Home", SiteActivity.decodeJsStringResult("\"River \\\"Chat\\\" \\\\ Home\""))
    }

    @Test
    fun boundedReaderCapsOutputSize() {
        val text = "abcdefghij"
        assertEquals("abcd", readBoundedText(StringReader(text), 4))
        assertEquals(text, readBoundedText(StringReader(text), 100))
    }

    @Test
    fun redactionCoversDashedKeysAndPrivateAndroidPaths() {
        val config = """
            mode = "network"
            secrets-dir = "/data/user/0/org.freenet.androidnode/files/freenet/prebuilt/data/secrets"
            custom = "/data/user/0/org.freenet.androidnode/cache/something"
        """.trimIndent()
        val redacted = redactConfigToml(config)
        assertFalse(redacted.contains("/data/user/0"))
        assertTrue(redacted.contains("secrets-dir = \"<redacted>\""))
        assertTrue(redacted.contains("custom = \"<redacted>\""))
    }

    @Test
    fun readLogTailDoesNotSplitUtf8Characters() {
        val tmp = File.createTempFile("logtail", ".log")
        try {
            tmp.writeText("abcééé", Charsets.UTF_8) // 'é' is 2 bytes in UTF-8
            val tail = readLogTail(tmp, 3)
            assertFalse(tail.contains('\uFFFD'))
            assertTrue(tail.endsWith("é"))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun dashboardStatusExtractsCountsAndNatHintInOnePass() {
        val html = """
            <html><head><title>(3) Dashboard</title></head>
            <body>
            <div>Only connected to gateways</div>
            <table>
              <tr class="peer-row"><td data-sort="10"></td><td data-sort="100"></td><td data-sort="250"></td><td data-sort="1"></td></tr>
            </table>
            <div>Upload <b>1.5 KB/s</b> Download <b>2.5 KB/s</b></div>
            </body></html>
        """.trimIndent()
        val parsed = DashboardStatus.parse(html)
        assertEquals(3, parsed.connections)
        assertEquals(1, parsed.peers)
        assertEquals(100L, parsed.bytesSent)
        assertEquals(250L, parsed.bytesReceived)
        assertEquals(1.5, parsed.uploadKbps, 0.001)
        assertEquals(2.5, parsed.downloadKbps, 0.001)
        assertEquals(
            "Only connected to gateways — no peer-to-peer connections yet",
            parsed.natHint,
        )
    }
}
