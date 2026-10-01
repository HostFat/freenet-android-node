package org.freenet.androidnode

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyCryptoTest {
    @Test
    fun twoPhonesCanTalkAndAChangeIsRejected() {
        val dir = File(System.getProperty("java.io.tmpdir"), "nearby-crypto-${System.nanoTime()}")
        val alice = NearbyIdentity.load(dir)
        val bob = NearbyIdentity.load(File(dir, "bob"))
        val fromAlice = NearbySession(alice, initiator = true)
        val fromBob = NearbySession(bob, initiator = false)
        val first = fromAlice.start()!!
        val second = fromBob.receive(first)!!
        val third = fromAlice.receive(second)!!
        assertEquals(null, fromBob.receive(third))
        assertTrue(fromAlice.established)
        assertTrue(fromBob.established)
        val sealed = fromAlice.seal("invite".toByteArray())
        assertEquals("invite", fromBob.open(sealed)!!.decodeToString())
        sealed[sealed.lastIndex] = (sealed[sealed.lastIndex].toInt() xor 1).toByte()
        assertEquals(null, fromBob.open(sealed))
        val text = "come in".toByteArray()
        val signed = nearbyChatSigned(ByteArray(16) { 3 }, 7, alice.ed25519Public, text)
        val signature = alice.sign(signed)
        assertTrue(nearbyVerify(alice.ed25519Public, signed, signature))
        assertFalse(nearbyVerify(bob.ed25519Public, signed, signature))
        dir.deleteRecursively()
    }
}
