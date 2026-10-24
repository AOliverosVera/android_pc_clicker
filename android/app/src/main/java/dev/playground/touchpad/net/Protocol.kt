package dev.playground.touchpad.net

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Wire protocol v2, shared with `pc-server/src/protocol.rs`; see
 * `docs/PROTOCOL.md`. Every datagram is `'T' 'P' <version> <type> ...` with
 * little-endian integers. Input only ever travels inside Secure packets
 * (see [dev.playground.touchpad.crypto.SecureChannel]).
 */
object Protocol {
    const val PORT = 47820
    const val VERSION: Byte = 2
    private const val HEADER_LEN = 4
    /** Requests that trigger replies are padded so replies are never larger. */
    const val PADDED_LEN = 64
    const val MAX_PHONE_NAME = 32
    private const val MAX_PC_NAME = 40

    private const val T_DISCOVER: Byte = 0x01
    private const val T_ANNOUNCE: Byte = 0x02
    private const val T_PAIR_START: Byte = 0x10
    private const val T_PAIR_COMMIT: Byte = 0x11
    private const val T_PAIR_NONCE: Byte = 0x12
    private const val T_PAIR_REVEAL: Byte = 0x13
    private const val T_PAIR_CONFIRM: Byte = 0x14
    private const val T_PAIR_RESULT: Byte = 0x15
    private const val T_HELLO: Byte = 0x20
    private const val T_WELCOME: Byte = 0x21
    private const val T_SECURE: Byte = 0x30

    enum class PairStatus { WAITING, ACCEPTED, REJECTED, CLOSED, BUSY, UNKNOWN }

    /** Packets the PC sends. Byte arrays are copies owned by the receiver. */
    sealed interface Reply {
        class Announce(val serverId: ByteArray, val pairingOpen: Boolean, val name: String) : Reply
        class PairCommit(val pairId: ByteArray, val pkb: ByteArray, val commit: ByteArray) : Reply
        class PairReveal(val pairId: ByteArray, val nb: ByteArray) : Reply
        class PairResult(val pairId: ByteArray, val status: PairStatus, val serverId: ByteArray, val tag: ByteArray) : Reply
        class Welcome(val pairId: ByteArray, val nc: ByteArray, val ns: ByteArray, val sessionId: Int, val tag: ByteArray) : Reply
        class Secure(val sessionId: Int) : Reply
    }

    // ------------------------------------------------------------ phone -> PC

    fun discover(): ByteArray = packet(T_DISCOVER, PADDED_LEN - HEADER_LEN) {}

    fun pairStart(pairId: ByteArray, pka: ByteArray, name: String): ByteArray {
        val nameBytes = truncateUtf8(name, MAX_PHONE_NAME)
        return packet(T_PAIR_START, 8 + 65 + 1 + nameBytes.size) {
            put(pairId).put(pka).put(nameBytes.size.toByte()).put(nameBytes)
        }
    }

    fun pairNonce(pairId: ByteArray, na: ByteArray) = packet(T_PAIR_NONCE, 24) { put(pairId).put(na) }

    fun pairConfirm(pairId: ByteArray, tag: ByteArray) = packet(T_PAIR_CONFIRM, 24) { put(pairId).put(tag) }

    fun hello(pairId: ByteArray, nc: ByteArray, tag: ByteArray) =
        packet(T_HELLO, PADDED_LEN - HEADER_LEN) { put(pairId).put(nc).put(tag) }

    fun secureHeader(): ByteArray = byteArrayOf('T'.code.toByte(), 'P'.code.toByte(), VERSION, T_SECURE)

    // ------------------------------------------------------------ PC -> phone

    fun decode(data: ByteArray, length: Int = data.size): Reply? {
        if (length < HEADER_LEN || data[0] != 'T'.code.toByte() || data[1] != 'P'.code.toByte() ||
            data[2] != VERSION
        ) return null
        val b = ByteBuffer.wrap(data, HEADER_LEN, length - HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            when (data[3]) {
                T_ANNOUNCE -> Reply.Announce(b.bytes(8), (b.get().toInt() and 1) == 1, b.string(MAX_PC_NAME) ?: return null)
                T_PAIR_COMMIT -> Reply.PairCommit(b.bytes(8), b.bytes(65), b.bytes(32))
                T_PAIR_REVEAL -> Reply.PairReveal(b.bytes(8), b.bytes(16))
                T_PAIR_RESULT -> Reply.PairResult(
                    pairId = b.bytes(8),
                    status = PairStatus.entries.getOrNull(b.get().toInt() and 0xFF) ?: return null,
                    serverId = b.bytes(8),
                    tag = b.bytes(16),
                )
                T_WELCOME -> Reply.Welcome(b.bytes(8), b.bytes(16), b.bytes(16), b.getInt(), b.bytes(16))
                T_SECURE -> Reply.Secure(b.getInt())
                else -> null
            }
        } catch (_: BufferUnderflowException) {
            null
        }
    }

    // ------------------------------------------------------------ inner messages

    enum class Button(val wire: Byte, val mask: Int) { LEFT(0, 0b01), RIGHT(1, 0b10) }

    object Inner {
        private const val HEARTBEAT: Byte = 0x01
        private const val MOVE: Byte = 0x02
        private const val BUTTON: Byte = 0x03
        private const val BYE: Byte = 0x04
        private const val FORGET: Byte = 0x05
        private const val HEARTBEAT_ACK: Byte = 0x81.toByte()

        /** [lastEvent] and [buttons] let the PC recover lost button packets. */
        fun heartbeat(seq: Int, lastEvent: Int, buttons: Int): ByteArray =
            inner(HEARTBEAT, 7) { putInt(seq).putShort(lastEvent.toShort()).put(buttons.toByte()) }

        fun move(dx: Int, dy: Int): ByteArray = inner(MOVE, 4) { putShort(dx.toShort()).putShort(dy.toShort()) }

        fun button(event: Int, button: Button, pressed: Boolean): ByteArray =
            inner(BUTTON, 4) { putShort(event.toShort()).put(button.wire).put(if (pressed) 1 else 0) }

        fun bye(): ByteArray = byteArrayOf(BYE)

        /** Asks the PC to forget this phone (sent when unpairing on the phone). */
        fun forget(): ByteArray = byteArrayOf(FORGET)

        /** Returns the acknowledged heartbeat sequence number, or null. */
        fun decodeAck(plaintext: ByteArray): Int? =
            if (plaintext.size >= 5 && plaintext[0] == HEARTBEAT_ACK) {
                ByteBuffer.wrap(plaintext, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            } else {
                null
            }

        private inline fun inner(type: Byte, payload: Int, body: ByteBuffer.() -> Unit): ByteArray {
            val buf = ByteBuffer.allocate(1 + payload).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(type).body()
            return buf.array()
        }
    }

    // ------------------------------------------------------------ helpers

    private inline fun packet(type: Byte, payload: Int, body: ByteBuffer.() -> Unit): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_LEN + payload).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('T'.code.toByte()).put('P'.code.toByte()).put(VERSION).put(type)
        buf.body()
        return buf.array() // remaining bytes stay zero: that is the padding
    }

    private fun ByteBuffer.bytes(n: Int): ByteArray = ByteArray(n).also { get(it) }

    private fun ByteBuffer.string(max: Int): String? {
        val len = get().toInt() and 0xFF
        if (len > max) return null
        val raw = bytes(len)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** UTF-8 bytes of [s], cut to at most [max] bytes without splitting a character. */
    fun truncateUtf8(s: String, max: Int): ByteArray {
        val out = StringBuilder()
        var size = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (size + n > max) break
            out.appendCodePoint(cp)
            size += n
            i += Character.charCount(cp)
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }
}
