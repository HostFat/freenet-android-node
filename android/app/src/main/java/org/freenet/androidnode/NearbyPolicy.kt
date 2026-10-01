package org.freenet.androidnode

import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONObject

internal object NearbyLimits {
    const val BLUETOOTH_MAX_BYTES = 8 * 1024 * 1024
    const val WIFI_MAX_BYTES = 32 * 1024 * 1024
    const val HARD_MAX_BYTES = 128 * 1024 * 1024
    const val DEFAULT_DAILY_CAP_MB = 20
    const val MIN_DAILY_CAP_MB = 0
    const val MAX_DAILY_CAP_MB = 500
    const val DEFAULT_SESSION_MINUTES = 30
    const val MIN_SESSION_MINUTES = 0
    const val MAX_SESSION_MINUTES = 120
    const val DEFAULT_HOP_LIMIT = 7
    const val MAX_HOP_LIMIT = 255
    const val DEFAULT_BLUETOOTH_MAX_MB = 8
    const val DEFAULT_WIFI_MAX_MB = 32
    const val MAX_SIZE_MB = 128
    const val WEBSOCKET_PORT = 7509
    const val FRAME_VERSION = 1
    const val TYPE_HELLO = 1
    const val TYPE_REQUEST = 2
    const val TYPE_REFUSE = 3
    const val TYPE_CONTRACT = 4
    const val TYPE_SEEK = 5
    const val TYPE_DELIVER = 6
    const val TYPE_UPDATE = 7
    const val TYPE_SEEK_REFUSE = 8
    const val HOP_HEADER_BYTES = 50
    const val REFUSE_OWNED_OFF = 1
    const val REFUSE_MISSING = 2
    const val REFUSE_DAILY_CAP = 3
    const val REFUSE_METERED = 4
    const val REFUSE_SESSION = 5
    const val REFUSE_TOO_LARGE = 6
    const val REFUSE_NODE_DOWN = 7
    const val REFUSE_SHARING_OFF = 8
    const val REFUSE_NODE_ERROR = 9
    const val REFUSE_UNAVAILABLE = 10
    const val NSD_TYPE = "_freenetnode._tcp."
    val SERVICE_UUID: java.util.UUID =
        java.util.UUID.fromString("f5e0e740-4b6a-4c2e-9a11-0b7c6d5e4f30")
}

internal data class NearbyDecision(
    val allowSend: Boolean,
    val allowFetch: Boolean,
    val refuseReason: Int,
) {
    val callNative: Boolean
        get() = allowSend || allowFetch
}

internal data class NearbyExportView(
    val status: String,
    val bytes: Long,
    val path: String,
    val message: String,
    val fetched: Boolean,
)

internal data class NearbyFrame(
    val type: Int,
    val payload: ByteArray,
)

internal data class NearbyDecodeBatch(
    val frames: List<NearbyFrame>,
    val overflow: Boolean,
)

internal fun nearbyNodeIsRunning(state: String?): Boolean =
    state == "RunningNetwork" || state == "RunningLocal"

internal fun coerceNearbyDailyCapMb(value: Int): Int =
    value.coerceIn(NearbyLimits.MIN_DAILY_CAP_MB, NearbyLimits.MAX_DAILY_CAP_MB)

internal fun coerceNearbySessionMinutes(value: Int): Int =
    value.coerceIn(NearbyLimits.MIN_SESSION_MINUTES, NearbyLimits.MAX_SESSION_MINUTES)

internal fun nearbyCapBytes(dailyCapMb: Int): Long {
    val cap = coerceNearbyDailyCapMb(dailyCapMb)
    if (cap == 0) return Long.MAX_VALUE
    return cap.toLong() * 1024L * 1024L
}

internal fun coerceNearbyHopLimit(value: Int): Int =
    value.coerceIn(0, NearbyLimits.MAX_HOP_LIMIT)

internal fun coerceNearbySizeMb(value: Int): Int =
    value.coerceIn(0, NearbyLimits.MAX_SIZE_MB)

internal fun nearbyMaxBytes(bluetoothMb: Int, wifiMb: Int, kind: String): Int {
    val megabytes = coerceNearbySizeMb(if (kind == "bluetooth") bluetoothMb else wifiMb)
    if (megabytes == 0) return NearbyLimits.HARD_MAX_BYTES
    return (megabytes.toLong() * 1024L * 1024L)
        .coerceAtMost(NearbyLimits.HARD_MAX_BYTES.toLong())
        .toInt()
}

/**
 * [hopsUsed] counts this phone. A limit of 1 never relays. Zero is no ceiling
 * from that phone. The sender's limit and this phone's limit both apply.
 */
internal fun nearbyForward(myLimit: Int, senderLimit: Int, hopsUsed: Int): Boolean {
    val mine = coerceNearbyHopLimit(myLimit)
    val sender = coerceNearbyHopLimit(senderLimit)
    if (mine == 1) return false
    if (mine != 0 && hopsUsed >= mine) return false
    if (sender != 0 && hopsUsed >= sender) return false
    return true
}

internal fun nearbySessionExpired(startedEpochMs: Long, sessionMinutes: Int, nowMs: Long): Boolean {
    if (startedEpochMs <= 0L) return false
    if (coerceNearbySessionMinutes(sessionMinutes) == 0) return false
    val windowMs = coerceNearbySessionMinutes(sessionMinutes) * 60_000L
    return nowMs >= startedEpochMs + windowMs
}

internal fun nearbyDayKey(nowMs: Long): String =
    Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

internal fun fetchedBytesToday(storedDay: String, storedBytes: Long, nowMs: Long): Long {
    if (storedDay != nearbyDayKey(nowMs)) return 0L
    return storedBytes.coerceAtLeast(0L)
}

/**
 * Decides the flags passed to the node before any client Get.
 * A missing contract reaches the node only when [fetchMissing] is still allowed.
 */
internal fun nearbyDecision(
    nodeRunning: Boolean,
    sessionExpired: Boolean,
    sendOwned: Boolean,
    fetchMissing: Boolean,
    fetchedBytes: Long,
    capBytes: Long,
    networkAllowed: Boolean,
    peersConnected: Boolean = true,
): NearbyDecision {
    if (!nodeRunning) {
        return NearbyDecision(false, false, NearbyLimits.REFUSE_NODE_DOWN)
    }
    if (sessionExpired) {
        return NearbyDecision(false, false, NearbyLimits.REFUSE_SESSION)
    }
    val allowFetch = fetchMissing && peersConnected && fetchedBytes < capBytes && networkAllowed
    if (sendOwned || allowFetch) {
        return NearbyDecision(sendOwned, allowFetch, 0)
    }
    val reason = when {
        fetchMissing && fetchedBytes >= capBytes -> NearbyLimits.REFUSE_DAILY_CAP
        fetchMissing && !networkAllowed -> NearbyLimits.REFUSE_METERED
        else -> NearbyLimits.REFUSE_SHARING_OFF
    }
    return NearbyDecision(false, false, reason)
}

internal fun nearbyRuntimePermissions(sdkInt: Int, bluetooth: Boolean, wifi: Boolean): List<String> {
    val permissions = ArrayList<String>()
    if (bluetooth && sdkInt >= 31) {
        permissions.add("android.permission.BLUETOOTH_SCAN")
        permissions.add("android.permission.BLUETOOTH_CONNECT")
        permissions.add("android.permission.BLUETOOTH_ADVERTISE")
    }
    if (bluetooth && sdkInt < 31) {
        permissions.add("android.permission.BLUETOOTH")
        permissions.add("android.permission.BLUETOOTH_ADMIN")
        permissions.add("android.permission.ACCESS_FINE_LOCATION")
    }
    if (wifi && sdkInt >= 33) {
        permissions.add("android.permission.NEARBY_WIFI_DEVICES")
    }
    return permissions
}

internal fun nearbyRefuseText(reason: Int): String = when (reason) {
    NearbyLimits.REFUSE_OWNED_OFF -> "That phone will not send contracts it already has."
    NearbyLimits.REFUSE_MISSING -> "That phone does not have this contract and will not download it."
    NearbyLimits.REFUSE_DAILY_CAP -> "That phone has reached its daily download cap."
    NearbyLimits.REFUSE_METERED -> "That phone will not use a metered network for this download."
    NearbyLimits.REFUSE_SESSION -> "That phone's sharing session has ended."
    NearbyLimits.REFUSE_TOO_LARGE -> "That contract is too large for this link."
    NearbyLimits.REFUSE_NODE_DOWN -> "That phone's node is not running."
    NearbyLimits.REFUSE_SHARING_OFF -> "That phone has contract sharing turned off."
    NearbyLimits.REFUSE_NODE_ERROR -> "That phone's node could not read the contract."
    NearbyLimits.REFUSE_UNAVAILABLE ->
        "That phone has a record of this contract but will not download a fresh copy."
    else -> "The nearby phone refused the request."
}

internal fun refuseForExportStatus(status: String): Int = when (status) {
    "absent" -> NearbyLimits.REFUSE_MISSING
    "refused" -> NearbyLimits.REFUSE_OWNED_OFF
    "unavailable" -> NearbyLimits.REFUSE_UNAVAILABLE
    "too_large" -> NearbyLimits.REFUSE_TOO_LARGE
    else -> NearbyLimits.REFUSE_NODE_ERROR
}

internal fun parseNearbyExport(json: String): NearbyExportView {
    val obj = runCatching { JSONObject(json) }.getOrNull()
        ?: return NearbyExportView("error", 0L, "", "The node returned an unreadable result.", false)
    return NearbyExportView(
        status = obj.optString("status", "error").ifBlank { "error" },
        bytes = obj.optLong("bytes", 0L).coerceAtLeast(0L),
        path = obj.optString("path", ""),
        message = obj.optString("message", ""),
        fetched = obj.optBoolean("fetched", false),
    )
}

internal fun parseNearbyImportMessage(json: String): String {
    val obj = runCatching { JSONObject(json) }.getOrNull()
        ?: return "This phone could not save the contract."
    val message = obj.optString("message", "")
    if (message.isNotBlank()) return message
    return if (obj.optString("status") == "imported") {
        "Contract saved on this phone."
    } else {
        "This phone could not save the contract."
    }
}

internal fun parseNearbyContractKey(raw: String): ByteArray? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val withoutSuffix = trimmed.substringBefore('?').substringBefore('#')
    val token = if (withoutSuffix.contains("://") || withoutSuffix.startsWith("/")) {
        withoutSuffix.trimEnd('/').substringAfterLast('/').substringAfterLast(':')
    } else {
        withoutSuffix.substringAfterLast(':')
    }
    return decodeNearbyKeyToken(token.trim())
}

internal fun decodeNearbyKeyToken(token: String): ByteArray? {
    val hex = token.removePrefix("0x").removePrefix("0X")
    if (hex.length == 64 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    val decoded = decodeBase58(token) ?: return null
    if (decoded.size == 32) return decoded
    return null
}

internal data class NearbyHop(
    val id: ByteArray,
    val senderLimit: Int,
    val hopsUsed: Int,
    val key: ByteArray,
    val body: ByteArray,
)

internal fun encodeNearbyHop(
    senderLimit: Int,
    hopsUsed: Int,
    id: ByteArray,
    key: ByteArray,
    body: ByteArray,
): ByteArray {
    val out = ByteArray(NearbyLimits.HOP_HEADER_BYTES + body.size)
    id.copyInto(out, 0, 0, 16)
    out[16] = coerceNearbyHopLimit(senderLimit).toByte()
    out[17] = hopsUsed.coerceIn(0, 255).toByte()
    key.copyInto(out, 18, 0, 32)
    body.copyInto(out, NearbyLimits.HOP_HEADER_BYTES)
    return out
}

internal fun parseNearbyHop(payload: ByteArray): NearbyHop? {
    if (payload.size < NearbyLimits.HOP_HEADER_BYTES) return null
    return NearbyHop(
        id = payload.copyOfRange(0, 16),
        senderLimit = payload[16].toInt() and 0xff,
        hopsUsed = payload[17].toInt() and 0xff,
        key = payload.copyOfRange(18, 50),
        body = payload.copyOfRange(NearbyLimits.HOP_HEADER_BYTES, payload.size),
    )
}

internal fun encodeNearbyDelivery(id: ByteArray, blob: ByteArray): ByteArray {
    val out = ByteArray(16 + blob.size)
    id.copyInto(out, 0, 0, 16)
    blob.copyInto(out, 16)
    return out
}

internal fun parseNearbyDelivery(payload: ByteArray): Pair<ByteArray, ByteArray>? {
    if (payload.size < 16) return null
    return payload.copyOfRange(0, 16) to payload.copyOfRange(16, payload.size)
}

internal fun parseNearbyImportKey(json: String): ByteArray? {
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (obj.optString("status") != "imported") return null
    return parseNearbyContractKey(obj.optString("key", ""))
}

internal fun nearbyKeyHex(key: ByteArray): String =
    key.joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun encodeNearbyFrame(type: Int, payload: ByteArray): ByteArray {
    val out = ByteArray(6 + payload.size)
    out[0] = NearbyLimits.FRAME_VERSION.toByte()
    out[1] = type.toByte()
    val len = payload.size
    out[2] = (len ushr 24).toByte()
    out[3] = (len ushr 16).toByte()
    out[4] = (len ushr 8).toByte()
    out[5] = len.toByte()
    payload.copyInto(out, 6)
    return out
}

internal class NearbyDecoder {
    private var buffer = ByteArray(256)
    private var size = 0

    fun push(chunk: ByteArray, length: Int = chunk.size, maxPayload: Int = NearbyLimits.WIFI_MAX_BYTES): NearbyDecodeBatch {
        if (length <= 0) return NearbyDecodeBatch(emptyList(), overflow = false)
        ensure(size + length)
        chunk.copyInto(buffer, destinationOffset = size, startIndex = 0, endIndex = length)
        size += length
        val frames = ArrayList<NearbyFrame>()
        var offset = 0
        while (size - offset >= 6) {
            val version = buffer[offset].toInt() and 0xff
            if (version != NearbyLimits.FRAME_VERSION) {
                offset += 1
                continue
            }
            val type = buffer[offset + 1].toInt() and 0xff
            val lenLong = Integer.toUnsignedLong(readInt(buffer, offset + 2))
            if (lenLong > maxPayload.toLong()) {
                size = 0
                return NearbyDecodeBatch(frames, overflow = true)
            }
            val len = lenLong.toInt()
            if (size - offset < 6 + len) break
            val start = offset + 6
            frames.add(NearbyFrame(type, buffer.copyOfRange(start, start + len)))
            offset += 6 + len
        }
        if (offset > 0) {
            buffer.copyInto(buffer, destinationOffset = 0, startIndex = offset, endIndex = size)
            size -= offset
        }
        return NearbyDecodeBatch(frames, overflow = false)
    }

    private fun ensure(needed: Int) {
        if (buffer.size >= needed) return
        var next = buffer.size
        while (next < needed) next *= 2
        buffer = buffer.copyOf(next)
    }
}

internal fun encodeBase58(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    var value = BigInteger(1, bytes)
    val base = BigInteger.valueOf(58)
    val chars = StringBuilder()
    while (value > BigInteger.ZERO) {
        val div = value.divideAndRemainder(base)
        chars.append(BASE58_ALPHABET[div[1].toInt()])
        value = div[0]
    }
    for (byte in bytes) {
        if (byte.toInt() != 0) break
        chars.append('1')
    }
    return chars.reverse().toString()
}

private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

private fun decodeBase58(text: String): ByteArray? {
    if (text.isEmpty()) return null
    var value = BigInteger.ZERO
    val base = BigInteger.valueOf(58)
    for (char in text) {
        val digit = BASE58_ALPHABET.indexOf(char)
        if (digit < 0) return null
        value = value.multiply(base).add(BigInteger.valueOf(digit.toLong()))
    }
    val magnitude = value.toByteArray().dropWhile { it.toInt() == 0 }.toByteArray()
    val leadingZeros = text.takeWhile { it == '1' }.length
    if (leadingZeros == text.length) return ByteArray(leadingZeros)
    val combined = ByteArray(leadingZeros + magnitude.size)
    magnitude.copyInto(combined, leadingZeros)
    return combined
}

private fun readInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)
