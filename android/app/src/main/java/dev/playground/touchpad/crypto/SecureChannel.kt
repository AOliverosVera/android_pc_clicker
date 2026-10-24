package dev.playground.touchpad.crypto

import dev.playground.touchpad.net.Protocol
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM envelope for one session; mirror of `pc-server/src/channel.rs`.
 *
 * Datagram: `header(4) | session_id u32 | counter u64 | ciphertext | tag(16)`,
 * with the first 16 bytes authenticated as associated data. The nonce is
 * `direction | 0 0 0 | counter`: every session has a fresh key and every
 * direction its own counter, so a nonce is never reused under a key.
 */
class SecureChannel(key: ByteArray, val sessionId: Int, role: Role) {

    enum class Role { PHONE, PC }

    private val key = SecretKeySpec(key, "AES")
    private val sendDir: Byte = if (role == Role.PHONE) DIR_PHONE_TO_PC else DIR_PC_TO_PHONE
    private val recvDir: Byte = if (role == Role.PHONE) DIR_PC_TO_PHONE else DIR_PHONE_TO_PC
    private var nextCounter = 1L
    private val replay = ReplayWindow()
    private val sealCipher = Cipher.getInstance(TRANSFORMATION)
    private val openCipher = Cipher.getInstance(TRANSFORMATION)

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        val counter = nextCounter
        check(counter != 0L) { "message counter exhausted" } // wraps only after 2^64 packets
        nextCounter = counter + 1
        val aad = aad(counter)
        sealCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(sendDir, counter)))
        sealCipher.updateAAD(aad)
        return aad + sealCipher.doFinal(plaintext)
    }

    /** The plaintext, or null unless the packet is authentic, ours, and new. */
    @Synchronized
    fun open(datagram: ByteArray, length: Int = datagram.size): ByteArray? {
        if (length < MIN_LEN) return null
        if (!MessageDigest.isEqual(datagram.copyOfRange(0, 4), Protocol.secureHeader())) return null
        val buf = ByteBuffer.wrap(datagram, 4, 12).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != sessionId) return null
        val counter = buf.long
        if (!replay.wouldAccept(counter)) return null
        val plaintext = try {
            openCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(recvDir, counter)))
            openCipher.updateAAD(datagram, 0, AAD_LEN)
            openCipher.doFinal(datagram, AAD_LEN, length - AAD_LEN)
        } catch (_: AEADBadTagException) {
            return null
        }
        replay.mark(counter) // only authentic packets may move the window
        return plaintext
    }

    private fun aad(counter: Long): ByteArray =
        ByteBuffer.allocate(AAD_LEN).order(ByteOrder.LITTLE_ENDIAN)
            .put(Protocol.secureHeader()).putInt(sessionId).putLong(counter).array()

    private fun nonce(direction: Byte, counter: Long): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(direction).put(0).put(0).put(0).putLong(counter).array()

    /**
     * Sliding anti-replay window as in IPsec (RFC 4303): each counter is
     * accepted once, with up to 63 packets of reordering. Counters are
     * unsigned 64-bit; they never get anywhere near the sign bit in practice.
     */
    private class ReplayWindow {
        private var highest = 0L
        private var seen = 0L // bit i = counter (highest - i) received

        fun wouldAccept(counter: Long): Boolean {
            if (counter <= 0L) return false
            if (counter > highest) return true
            val age = highest - counter
            return age < 64 && (seen and (1L shl age.toInt())) == 0L
        }

        fun mark(counter: Long) {
            if (counter > highest) {
                val shift = counter - highest
                seen = if (shift >= 64) 0L else seen shl shift.toInt()
                seen = seen or 1L
                highest = counter
            } else {
                seen = seen or (1L shl (highest - counter).toInt())
            }
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DIR_PHONE_TO_PC: Byte = 1
        private const val DIR_PC_TO_PHONE: Byte = 2
        private const val AAD_LEN = 16
        private const val MIN_LEN = AAD_LEN + 1 + 16
    }
}
