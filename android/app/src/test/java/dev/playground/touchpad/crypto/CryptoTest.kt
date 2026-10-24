package dev.playground.touchpad.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the derivations against docs/test-vectors.txt, which was computed by
 * an independent implementation (Python) and is also checked by the Rust tests.
 */
class CryptoTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun fill(n: Int, v: Int) = ByteArray(n) { v.toByte() }

    @Test fun hkdfMatchesRfc5869TestCase1() {
        val okm = Crypto.hkdf32(ByteArray(13) { it.toByte() }, fill(22, 0x0b), ByteArray(10) { (0xf0 + it).toByte() })
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", hex(okm))
    }

    @Test fun hmacMatchesRfc4231TestCase2Truncated() {
        val tag = Crypto.hmacTag("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c7", hex(tag))
        assertTrue(Crypto.verifyTag("Jefe".toByteArray(), tag, "what do ya want for nothing?".toByteArray()))
        assertFalse(Crypto.verifyTag("Jefe".toByteArray(), tag.copyOf().also { it[15] = (it[15] + 1).toByte() }, "what do ya want for nothing?".toByteArray()))
    }

    @Test fun derivationsMatchSharedVectors() {
        val pka = byteArrayOf(4) + fill(64, 0x11)
        val pkb = byteArrayOf(4) + fill(64, 0x22)
        val na = fill(16, 0x33)
        val nb = fill(16, 0x44)
        val pairId = fill(8, 0x55)
        val z = fill(32, 0x66)
        val serverId = fill(8, 0x77)
        val nc = fill(16, 0x88)
        val ns = fill(16, 0x99)
        val sid = 0x01020304
        val k = Crypto.pairingKey(z, na, nb, pairId, pka, pkb)
        val report = listOf(
            "commit=" + hex(Crypto.commitment(pkb, pka, nb)),
            "code=" + Crypto.verificationCode(pka, pkb, na, nb),
            "k=" + hex(k),
            "phone_confirm=" + hex(Crypto.phoneConfirmTag(k)),
            "pc_confirm=" + hex(Crypto.pcConfirmTag(k, serverId)),
            "hello=" + hex(Crypto.helloTag(k, pairId, nc)),
            "welcome=" + hex(Crypto.welcomeTag(k, pairId, nc, ns, sid)),
            "ks=" + hex(Crypto.sessionKey(k, nc, ns, pairId, sid)),
        ).joinToString("\n")
        assertEquals(vectors()[0], report)
    }

    @Test fun ecdhAgreesBothWays() {
        val a = EcdhKeyPair.generate()
        val b = EcdhKeyPair.generate()
        assertEquals(65, a.public.size)
        assertArrayEquals(a.sharedSecret(b.public), b.sharedSecret(a.public))
    }

    @Test fun invalidPeerPointsAreRejected() {
        val a = EcdhKeyPair.generate()
        val b = EcdhKeyPair.generate().public
        assertNull("off curve", a.sharedSecret(b.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }))
        assertNull("all zero", a.sharedSecret(ByteArray(65)))
        assertNull("compressed prefix", a.sharedSecret(b.copyOf().also { it[0] = 2 }))
        assertNull("too short", a.sharedSecret(b.copyOf(64)))
        assertNull("coordinate >= p", a.sharedSecret(byteArrayOf(4) + fill(64, 0xFF)))
        assertNotNull(a.sharedSecret(b))
    }

    companion object {
        fun vectors(): List<String> {
            val path = System.getProperty("touchpad.vectors") ?: error("touchpad.vectors not set (see app/build.gradle.kts)")
            return File(path).readText().trimEnd().replace("\r\n", "\n").split("\n--\n")
        }
    }
}
