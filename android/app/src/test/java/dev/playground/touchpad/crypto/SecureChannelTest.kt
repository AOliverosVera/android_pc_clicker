package dev.playground.touchpad.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SecureChannelTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val key = ByteArray(32) { 7 }
    private fun phone() = SecureChannel(key, 9, SecureChannel.Role.PHONE)
    private fun pc() = SecureChannel(key, 9, SecureChannel.Role.PC)

    @Test fun roundtripsBothWays() {
        val phone = phone()
        val pc = pc()
        assertArrayEquals("hi pc".toByteArray(), pc.open(phone.seal("hi pc".toByteArray())))
        assertArrayEquals("hi phone".toByteArray(), phone.open(pc.seal("hi phone".toByteArray())))
    }

    @Test fun sealedBytesMatchSharedVectors() {
        val ab = ByteArray(32) { 0xAB.toByte() }
        val move = SecureChannel(ab, 0x01020304, SecureChannel.Role.PHONE).seal(byteArrayOf(0x02, 0x05, 0x00, 0xFD.toByte(), 0xFF.toByte()))
        val ack = SecureChannel(ab, 0x01020304, SecureChannel.Role.PC).seal(byteArrayOf(0x81.toByte(), 0x2A, 0, 0, 0))
        assertEquals(CryptoTest.vectors()[1], "phone_move=${hex(move)}\npc_ack=${hex(ack)}")
    }

    @Test fun replaysAreRejected() {
        val p = phone().seal(byteArrayOf(1))
        val pc = pc()
        assertNotNull(pc.open(p))
        assertNull(pc.open(p))
    }

    @Test fun reorderingWithinTheWindowIsAcceptedOnce() {
        val phone = phone()
        val packets = List(70) { phone.seal(byteArrayOf(1)) }
        val pc = pc()
        assertNotNull(pc.open(packets[69]))
        assertNotNull(pc.open(packets[10]))
        assertNull(pc.open(packets[10]))
        assertNull("too old", pc.open(packets[5]))
    }

    @Test fun everyBitFlipIsRejected() {
        val p = phone().seal(byteArrayOf(2, 5, 0, 1, 0))
        val pc = pc()
        for (i in p.indices) for (bit in 0 until 8) {
            val q = p.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
            assertNull("byte $i bit $bit", pc.open(q))
        }
        assertNotNull(pc.open(p))
    }

    @Test fun wrongKeySessionOrReflectionIsRejected() {
        val p = phone().seal(byteArrayOf(1))
        assertNull(SecureChannel(ByteArray(32) { 8 }, 9, SecureChannel.Role.PC).open(p))
        assertNull(SecureChannel(key, 10, SecureChannel.Role.PC).open(p))
        assertNull("reflected back at the phone", phone().open(p))
    }

    @Test fun honoursLengthInsideLargerBuffer() {
        val p = pc().seal(byteArrayOf(0x81.toByte(), 1, 0, 0, 0))
        val buffer = ByteArray(2048).also { p.copyInto(it) }
        assertNotNull(phone().open(buffer, p.size))
    }
}
