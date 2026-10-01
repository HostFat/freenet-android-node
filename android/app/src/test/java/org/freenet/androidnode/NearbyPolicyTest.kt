package org.freenet.androidnode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPolicyTest {
    @Test
    fun missingContractIsNotRequestedWhenDownloadIsOff() {
        val blocked = nearbyDecision(
            nodeRunning = true,
            sessionExpired = false,
            fetchMissing = false,
            fetchedBytes = 0L,
            capBytes = nearbyCapBytes(20),
            networkAllowed = true,
        )
        assertTrue(blocked.callNative)
        assertTrue(blocked.allowSend)
        assertFalse(blocked.allowFetch)

        val sendOnly = nearbyDecision(
            nodeRunning = true,
            sessionExpired = false,
            fetchMissing = false,
            fetchedBytes = 0L,
            capBytes = nearbyCapBytes(20),
            networkAllowed = true,
        )
        assertTrue(sendOnly.callNative)
        assertTrue(sendOnly.allowSend)
        assertFalse(sendOnly.allowFetch)
    }

    @Test
    fun downloadStaysOffWhenTheCapOrTheNetworkBlocksIt() {
        val capped = nearbyDecision(
            nodeRunning = true,
            sessionExpired = false,
            fetchMissing = true,
            fetchedBytes = nearbyCapBytes(20),
            capBytes = nearbyCapBytes(20),
            networkAllowed = true,
        )
        assertTrue(capped.allowSend)
        assertFalse(capped.allowFetch)

        val metered = nearbyDecision(
            nodeRunning = true,
            sessionExpired = false,
            fetchMissing = true,
            fetchedBytes = 0L,
            capBytes = nearbyCapBytes(20),
            networkAllowed = false,
        )
        assertTrue(metered.allowSend)
        assertFalse(metered.allowFetch)

        val allowed = nearbyDecision(
            nodeRunning = true,
            sessionExpired = false,
            fetchMissing = true,
            fetchedBytes = nearbyCapBytes(20) - 1,
            capBytes = nearbyCapBytes(20),
            networkAllowed = true,
        )
        assertTrue(allowed.allowFetch)
    }

    @Test
    fun aStoppedNodeOrEndedSessionNeverCallsTheNode() {
        val down = nearbyDecision(
            nodeRunning = false,
            sessionExpired = false,
            fetchMissing = true,
            fetchedBytes = 0L,
            capBytes = nearbyCapBytes(20),
            networkAllowed = true,
        )
        assertEquals(NearbyLimits.REFUSE_NODE_DOWN, down.refuseReason)
        assertFalse(down.callNative)

        val ended = nearbyDecision(
            nodeRunning = true,
            sessionExpired = true,
            fetchMissing = true,
            fetchedBytes = 0L,
            capBytes = nearbyCapBytes(20),
            networkAllowed = true,
        )
        assertEquals(NearbyLimits.REFUSE_SESSION, ended.refuseReason)
        assertTrue(nearbySessionExpired(1_000L, 30, 1_000L + 30 * 60_000L))
        assertFalse(nearbySessionExpired(1_000L, 30, 1_000L + 30 * 60_000L - 1))
        assertFalse(nearbyNodeIsRunning("Stopped"))
        assertTrue(nearbyNodeIsRunning("RunningLocal"))
    }

    @Test
    fun framesRoundTripAcrossPartialReadsAndRejectAnOversizedLength() {
        val frame = encodeNearbyFrame(NearbyLimits.TYPE_HELLO, "phone".encodeToByteArray())
        val decoder = NearbyDecoder()
        assertTrue(decoder.push(frame.copyOfRange(0, 3), 3).frames.isEmpty())
        val done = decoder.push(frame.copyOfRange(3, frame.size), frame.size - 3)
        assertEquals(1, done.frames.size)
        assertEquals(NearbyLimits.TYPE_HELLO, done.frames[0].type)
        assertEquals("phone", done.frames[0].payload.decodeToString())

        val hostile = byteArrayOf(1, 4, 0x7f, 0, 0, 0)
        val overflow = NearbyDecoder().push(hostile, hostile.size, maxPayload = 32)
        assertTrue(overflow.overflow)
        assertEquals(NearbyLimits.REFUSE_MISSING, refuseForExportStatus("absent"))
        assertEquals(NearbyLimits.REFUSE_UNAVAILABLE, refuseForExportStatus("unavailable"))
    }

    @Test
    fun contractKeysAcceptHexBase58AndTheLastUrlSegment() {
        val key = ByteArray(32) { index -> if (index == 0) 0 else (index + 1).toByte() }
        val hex = nearbyKeyHex(key)
        assertTrue(hex.startsWith("00"))
        assertTrue(parseNearbyContractKey(hex)!!.contentEquals(key))
        assertTrue(parseNearbyContractKey("0x$hex")!!.contentEquals(key))
        val encoded = encodeBase58(key)
        assertTrue(parseNearbyContractKey(encoded)!!.contentEquals(key))
        assertTrue(
            parseNearbyContractKey("https://127.0.0.1:7509/contract/$encoded")!!.contentEquals(key),
        )
        assertNull(parseNearbyContractKey("not a key"))
        val day = java.time.Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
        assertEquals(0L, fetchedBytesToday("2026-09-30", 40L, day))
        assertEquals("2026-10-01", nearbyDayKey(day))
        assertEquals(40L, fetchedBytesToday("2026-10-01", 40L, day))
        assertEquals(
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"),
            nearbyRuntimePermissions(31, bluetooth = true, wifi = false),
        )
        assertTrue(nearbyRuntimePermissions(33, bluetooth = false, wifi = true).contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertEquals(0, coerceNearbyDailyCapMb(0))
        assertEquals(0, coerceNearbySessionMinutes(0))
        assertEquals(120, coerceNearbySessionMinutes(500))
        assertFalse(nearbySessionExpired(1_000L, 0, 1_000L + 24 * 60 * 60_000L))
    }

    @Test
    fun theSameStateVersionCountsAsAlreadyStored() {
        val message = "New state version 30000394 must be higher than current version 30000394"
        assertTrue(nearbySameVersion(message))
        assertFalse(
            nearbySameVersion("New state version 5 must be higher than current version 4"),
        )
    }

    @Test
    fun anInviteAddressOpensOnThisPhone() {
        val appKey = "ab".repeat(32)
        val remote = "https://gateway.example/v1/contract/web/$appKey/?invitation=abc"
        assertEquals(
            "http://127.0.0.1:7509/v1/contract/web/$appKey/?invitation=abc",
            nearbyLocalOpenUrl(remote),
        )
        assertEquals(
            "http://127.0.0.1:7509/v1/contract/web/$appKey/",
            nearbyLocalOpenUrl(appKey),
        )
        assertEquals(null, nearbyLocalOpenUrl("hello"))
    }

    @Test
    fun aContractPageAddressYieldsEveryKey() {
        val appKey = "ab".repeat(32)
        val roomKey = "cd".repeat(32)
        val keys = nearbyContractKeysInUrl(
            "http://127.0.0.1:7509/v1/contract/web/$appKey/room/$roomKey/?x=1",
        )
        assertEquals(2, keys.size)
        assertEquals(appKey, nearbyKeyHex(keys[0]))
        assertEquals(roomKey, nearbyKeyHex(keys[1]))
        assertTrue(nearbyContractKeysInUrl("http://127.0.0.1:7509/").isEmpty())
    }

    @Test
    fun noNetworkIsOfflineAndAMeteredNetworkStaysBlocked() {
        val offline = ConnectivitySnapshot(
            available = false,
            validated = false,
            wifi = false,
            metered = false,
            vpn = false,
            networkType = "None",
            activeNetwork = null,
        )
        assertEquals(NetworkStartBlock.Offline, networkStartBlock(offline, NetworkDataPolicy.AnyValidated))
        val metered = offline.copy(available = true, validated = true, metered = true, networkType = "Cellular")
        assertEquals(
            NetworkStartBlock.Metered,
            networkStartBlock(metered, NetworkDataPolicy.UnmeteredOnly),
        )
        assertEquals(null, networkStartBlock(metered, NetworkDataPolicy.AnyValidated))
    }

    @Test
    fun hopLimitStopsAtTheStricterPhone() {
        assertFalse(nearbyForward(myLimit = 1, senderLimit = 8, hopsUsed = 1))
        assertFalse(nearbyForward(myLimit = 0, senderLimit = 1, hopsUsed = 1))
        assertTrue(nearbyForward(myLimit = 0, senderLimit = 0, hopsUsed = 4))
        assertTrue(nearbyForward(myLimit = 7, senderLimit = 7, hopsUsed = 6))
        assertFalse(nearbyForward(myLimit = 7, senderLimit = 7, hopsUsed = 7))
        assertFalse(nearbyForward(myLimit = 1, senderLimit = 0, hopsUsed = 1))
        assertEquals(Long.MAX_VALUE, nearbyCapBytes(0))

        val id = ByteArray(16) { 7 }
        val key = ByteArray(32) { 3 }
        val encoded = encodeNearbyHop(7, 2, id, key, byteArrayOf(9, 8))
        val parsed = parseNearbyHop(encoded)
        assertEquals(7, parsed!!.senderLimit)
        assertEquals(2, parsed.hopsUsed)
        assertTrue(parsed.id.contentEquals(id))
        assertTrue(parsed.key.contentEquals(key))
        assertTrue(parsed.body.contentEquals(byteArrayOf(9, 8)))

        val offline = nearbyDecision(
            nodeRunning = true,
            sessionExpired = false,
            fetchMissing = true,
            fetchedBytes = 0L,
            capBytes = nearbyCapBytes(20),
            networkAllowed = true,
            peersConnected = false,
        )
        assertTrue(offline.allowSend)
        assertFalse(offline.allowFetch)
    }
}
