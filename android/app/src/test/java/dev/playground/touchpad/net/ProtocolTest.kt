package dev.playground.touchpad.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte layouts must match `pc-server/src/protocol.rs` and docs/PROTOCOL.md. */
class ProtocolTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun header(type: Int) = bytes(0x54, 0x50, 0x02, type)

    @Test fun discoverIsPaddedTo64Bytes() {
        val d = Protocol.discover()
        assertEquals(64, d.size)
        assertArrayEquals(header(0x01), d.copyOf(4))
        assertTrue(d.drop(4).all { it == 0.toByte() })
    }

    @Test fun helloIsPaddedTo64Bytes() {
        val h = Protocol.hello(ByteArray(8) { 1 }, ByteArray(16) { 2 }, ByteArray(16) { 3 })
        assertEquals(64, h.size)
        assertArrayEquals(header(0x20) + ByteArray(8) { 1 } + ByteArray(16) { 2 } + ByteArray(16) { 3 }, h.copyOf(44))
    }

    @Test fun pairStartLayout() {
        val p = Protocol.pairStart(ByteArray(8) { 7 }, ByteArray(65) { 4 }, "Pixel")
        assertArrayEquals(header(0x10) + ByteArray(8) { 7 } + ByteArray(65) { 4 } + bytes(5) + "Pixel".toByteArray(), p)
    }

    @Test fun phoneNameIsCutOnACharacterBoundary() {
        val p = Protocol.pairStart(ByteArray(8), ByteArray(65), "ñ".repeat(40))
        val len = p[4 + 8 + 65].toInt()
        assertTrue(len <= Protocol.MAX_PHONE_NAME)
        assertEquals("ñ".repeat(len / 2), String(p, 4 + 8 + 65 + 1, len, Charsets.UTF_8))
    }

    @Test fun innerMessagesMatchDocumentedBytes() {
        assertArrayEquals(bytes(0x02, 0x05, 0x00, 0xFD, 0xFF), Protocol.Inner.move(5, -3))
        assertArrayEquals(bytes(0x07, 0xFD, 0xFF, 0x78, 0x00), Protocol.Inner.scroll(-3, 120))
        assertArrayEquals(bytes(0x01, 0x2A, 0, 0, 0, 0x07, 0x00, 0x01), Protocol.Inner.heartbeat(42, 7, 1))
        assertArrayEquals(bytes(0x03, 0x07, 0x00, 0x01, 0x01), Protocol.Inner.button(7, Protocol.Button.RIGHT, true))
        assertArrayEquals(bytes(0x03, 0xFF, 0xFF, 0x00, 0x00), Protocol.Inner.button(0xFFFF, Protocol.Button.LEFT, false))
        assertArrayEquals(bytes(0x04), Protocol.Inner.bye())
        assertArrayEquals(bytes(0x05), Protocol.Inner.forget())
        assertEquals(42, Protocol.Inner.decodeAck(bytes(0x81, 0x2A, 0, 0, 0)))
        assertNull(Protocol.Inner.decodeAck(bytes(0x02, 0x2A, 0, 0, 0)))
    }

    /** The same bytes as `keys_match_documented_bytes` in protocol.rs. */
    @Test fun keysMatchDocumentedBytes() {
        val keys = Protocol.Inner.keys(
            0x0102,
            listOf(
                Protocol.KeyStroke.text('á'.code),
                Protocol.KeyStroke.special(Protocol.Special.f(1), Protocol.Mods.CTRL or Protocol.Mods.SHIFT),
            ),
        )
        assertArrayEquals(
            bytes(0x06, 0x02, 0x01, 0x02, 0x00, 0x00, 0xE1, 0x00, 0x00, 0x00, 0x03, 0x01, 0x31, 0x00, 0x00, 0x00),
            keys,
        )
        val emoji = Protocol.Inner.keys(0, listOf(Protocol.KeyStroke.text("😀".codePointAt(0))))
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0xF6, 0x01, 0x00), emoji.copyOfRange(4, 10))
    }

    @Test fun decodesAnnounceWithUtf8Name() {
        val name = "Escritorio-Ñandú".toByteArray()
        val r = Protocol.decode(header(0x02) + ByteArray(8) { 9 } + bytes(1, name.size) + name) as Protocol.Reply.Announce
        assertEquals("Escritorio-Ñandú", r.name)
        assertTrue(r.pairingOpen)
        assertArrayEquals(ByteArray(8) { 9 }, r.serverId)
    }

    @Test fun decodesWelcomeAndResult() {
        val w = Protocol.decode(
            header(0x21) + ByteArray(8) { 1 } + ByteArray(16) { 2 } + ByteArray(16) { 3 } + bytes(4, 3, 2, 1) + ByteArray(16) { 5 },
        ) as Protocol.Reply.Welcome
        assertEquals(0x01020304, w.sessionId)
        val r = Protocol.decode(header(0x15) + ByteArray(8) + bytes(3) + ByteArray(8) + ByteArray(16)) as Protocol.Reply.PairResult
        assertEquals(Protocol.PairStatus.CLOSED, r.status)
    }

    @Test fun rejectsMalformedPackets() {
        val announce = header(0x02) + ByteArray(8) + bytes(0, 2) + "PC".toByteArray()
        assertNull(Protocol.decode(byteArrayOf(0x58) + announce.copyOfRange(1, announce.size))) // magic
        assertNull(Protocol.decode(announce.copyOf().also { it[2] = 1 })) // old version
        assertNull(Protocol.decode(header(0x7F))) // unknown type
        for (len in 0 until announce.size) assertNull("prefix $len", Protocol.decode(announce, len))
        assertNull(Protocol.decode(header(0x02) + ByteArray(8) + bytes(0, 41) + ByteArray(41))) // name too long
        assertNull(Protocol.decode(header(0x02) + ByteArray(8) + bytes(0, 2, 0xC3, 0x28))) // invalid UTF-8
        assertNull(Protocol.decode(header(0x15) + ByteArray(8) + bytes(9) + ByteArray(24))) // unknown status
    }
}
