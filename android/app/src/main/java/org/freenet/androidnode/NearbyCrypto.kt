package org.freenet.androidnode

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

internal class NearbyIdentity(
    private val x25519Private: X25519PrivateKeyParameters,
    private val ed25519Private: Ed25519PrivateKeyParameters,
) {
    val x25519Public: ByteArray = x25519Private.generatePublicKey().encoded
    val ed25519Public: ByteArray = ed25519Private.generatePublicKey().encoded
    val fingerprint: String = nearbyFingerprint(ed25519Public)

    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, ed25519Private)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun sharedSecret(remoteX25519Public: ByteArray): ByteArray = dh(x25519Private, remoteX25519Public)

    companion object {
        private const val FILE_NAME = "nearby_identity.bin"

        fun load(directory: File): NearbyIdentity {
            val file = File(directory, FILE_NAME)
            if (file.isFile && file.length() == 64L) {
                val bytes = file.readBytes()
                return NearbyIdentity(
                    X25519PrivateKeyParameters(bytes, 0),
                    Ed25519PrivateKeyParameters(bytes, 32),
                )
            }
            val random = SecureRandom()
            val xSeed = ByteArray(32).also { random.nextBytes(it) }
            val edSeed = ByteArray(32).also { random.nextBytes(it) }
            file.parentFile?.mkdirs()
            file.writeBytes(xSeed + edSeed)
            return NearbyIdentity(
                X25519PrivateKeyParameters(xSeed, 0),
                Ed25519PrivateKeyParameters(edSeed, 0),
            )
        }
    }
}

internal fun nearbyFingerprint(ed25519Public: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(ed25519Public)
    return digest.take(8).joinToString(" ") { "%02x".format(it) }
}

internal fun nearbyVerify(ed25519Public: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
    if (ed25519Public.size != 32 || signature.size != 64) return false
    val signer = Ed25519Signer()
    signer.init(false, Ed25519PublicKeyParameters(ed25519Public, 0))
    signer.update(data, 0, data.size)
    return signer.verifySignature(signature)
}

internal fun nearbyChatSigned(id: ByteArray, senderLimit: Int, senderPublic: ByteArray, text: ByteArray): ByteArray =
    id + byteArrayOf(senderLimit.coerceIn(0, 255).toByte()) + senderPublic + text

internal class NearbySession(
    identity: NearbyIdentity,
    private val initiator: Boolean,
) {
    private val handshake = NoiseHandshake(identity, initiator)
    var peerEdPublic: ByteArray? = null
        private set
    val established: Boolean get() = handshake.established
    var failed: Boolean = false
        private set

    fun start(): ByteArray? = if (initiator) handshake.writeMessage1() else null

    fun receive(message: ByteArray): ByteArray? {
        if (failed) return null
        val reply = runCatching { handshake.read(message) }.getOrElse {
            failed = true
            return null
        }
        if (handshake.established) peerEdPublic = handshake.peerStatic
        return reply
    }

    fun seal(plain: ByteArray): ByteArray = handshake.seal(plain)

    fun open(cipher: ByteArray): ByteArray? = runCatching { handshake.open(cipher) }.getOrElse {
        failed = true
        null
    }
}

private class NoiseHandshake(
    private val identity: NearbyIdentity,
    private val initiator: Boolean,
) {
    private var h = sha256(PROTOCOL.toByteArray(Charsets.UTF_8))
    private var ck = h.copyOf()
    private var k: ByteArray? = null
    private var n = 0L
    private var localEphemeral: X25519PrivateKeyParameters? = null
    private var remoteEphemeral: ByteArray? = null
    private var sendKey: ByteArray? = null
    private var receiveKey: ByteArray? = null
    private var sendNonce = 0L
    private var receiveNonce = 0L
    private var step = 0
    var established: Boolean = false
        private set
    var peerStatic: ByteArray? = null
        private set

    fun writeMessage1(): ByteArray {
        val ephemeral = newX25519()
        localEphemeral = ephemeral
        val pub = ephemeral.generatePublicKey().encoded
        mixHash(pub)
        step = 1
        return pub
    }

    fun read(message: ByteArray): ByteArray? = when (step) {
        0 -> if (initiator) error("unexpected handshake") else readMessage1(message)
        1 -> if (initiator) readMessage2(message) else error("unexpected handshake")
        2 -> if (initiator) error("unexpected handshake") else readMessage3(message)
        else -> error("handshake finished")
    }

    private fun readMessage1(message: ByteArray): ByteArray {
        if (message.size != 32) error("bad handshake")
        remoteEphemeral = message.copyOf()
        mixHash(message)
        return writeMessage2()
    }

    private fun writeMessage2(): ByteArray {
        val ephemeral = newX25519()
        localEphemeral = ephemeral
        val pub = ephemeral.generatePublicKey().encoded
        mixHash(pub)
        mixKey(dh(ephemeral, remoteEphemeral!!))
        val encryptedStatic = encryptAndHash(identity.x25519Public)
        mixKey(identity.sharedSecret(remoteEphemeral!!))
        step = 2
        return pub + encryptedStatic
    }

    private fun readMessage2(message: ByteArray): ByteArray {
        if (message.size != 32 + 48) error("bad handshake")
        val remotePub = message.copyOfRange(0, 32)
        remoteEphemeral = remotePub
        mixHash(remotePub)
        mixKey(dh(localEphemeral!!, remotePub))
        val remoteStatic = decryptAndHash(message.copyOfRange(32, message.size))
        if (remoteStatic.size != 32) error("bad static key")
        peerStatic = remoteStatic
        mixKey(dh(localEphemeral!!, remoteStatic))
        return writeMessage3()
    }

    private fun writeMessage3(): ByteArray {
        val encrypted = encryptAndHash(identity.x25519Public)
        mixKey(identity.sharedSecret(remoteEphemeral!!))
        split()
        step = 3
        established = true
        return encrypted
    }

    private fun readMessage3(message: ByteArray): ByteArray? {
        val remoteStatic = decryptAndHash(message)
        if (remoteStatic.size != 32) error("bad static key")
        peerStatic = remoteStatic
        mixKey(dh(localEphemeral!!, remoteStatic))
        split()
        step = 3
        established = true
        return null
    }

    fun seal(plain: ByteArray): ByteArray {
        val key = sendKey ?: error("session not ready")
        val cipher = aead(true, key, sendNonce, ByteArray(0), plain)
        sendNonce += 1
        return cipher
    }

    fun open(cipher: ByteArray): ByteArray {
        val key = receiveKey ?: error("session not ready")
        val plain = aead(false, key, receiveNonce, ByteArray(0), cipher)
        receiveNonce += 1
        return plain
    }

    private fun split() {
        val (first, second) = hkdf(ck, ByteArray(0))
        if (initiator) {
            sendKey = first
            receiveKey = second
        } else {
            sendKey = second
            receiveKey = first
        }
    }

    private fun encryptAndHash(plain: ByteArray): ByteArray {
        val cipher = if (k == null) plain else {
            val out = aead(true, k!!, n, h, plain)
            n += 1
            out
        }
        mixHash(cipher)
        return cipher
    }

    private fun decryptAndHash(cipher: ByteArray): ByteArray {
        val plain = if (k == null) cipher else {
            val out = aead(false, k!!, n, h, cipher)
            n += 1
            out
        }
        mixHash(cipher)
        return plain
    }

    private fun mixHash(data: ByteArray) {
        h = sha256(h + data)
    }

    private fun mixKey(material: ByteArray) {
        val (nextCk, nextK) = hkdf(ck, material)
        ck = nextCk
        k = nextK
        n = 0
    }

    companion object {
        private const val PROTOCOL = "Noise_XX_25519_ChaChaPoly_SHA256"

        private fun newX25519(): X25519PrivateKeyParameters {
            val seed = ByteArray(32)
            SecureRandom().nextBytes(seed)
            return X25519PrivateKeyParameters(seed, 0)
        }
    }
}

private fun dh(privateKey: X25519PrivateKeyParameters, publicKey: ByteArray): ByteArray {
    val agreement = X25519Agreement()
    agreement.init(privateKey)
    val shared = ByteArray(agreement.agreementSize)
    agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), shared, 0)
    return shared
}

private fun hkdf(chainingKey: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
    val temp = hmac(chainingKey, input)
    val first = hmac(temp, byteArrayOf(0x01))
    val second = hmac(temp, first + byteArrayOf(0x02))
    return first to second
}

private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

private fun aead(encrypt: Boolean, key: ByteArray, nonceCounter: Long, ad: ByteArray, input: ByteArray): ByteArray {
    val cipher = ChaCha20Poly1305()
    val nonce = ByteArray(12)
    for (index in 0 until 8) {
        nonce[4 + index] = ((nonceCounter shr (8 * index)) and 0xff).toByte()
    }
    cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, ad))
    val out = ByteArray(cipher.getOutputSize(input.size))
    val produced = cipher.processBytes(input, 0, input.size, out, 0)
    cipher.doFinal(out, produced)
    return out
}
