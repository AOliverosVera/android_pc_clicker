package dev.playground.touchpad.net

import dev.playground.touchpad.crypto.Crypto
import dev.playground.touchpad.crypto.EcdhKeyPair
import dev.playground.touchpad.crypto.SecureChannel
import java.security.MessageDigest

/**
 * The phone's side of pairing: ECDH P-256 plus "numeric comparison" as in
 * Bluetooth Secure Simple Pairing. The PC commits to its nonce before seeing
 * ours, so a man in the middle can't search for keys that make both screens
 * show the same 6-digit code; the person comparing the codes is the check.
 *
 * Pure logic (no Android APIs), so it is tested on the JVM against the real
 * server. Every method returns the next packet to send, or a result.
 */
class PairingAttempt(phoneName: String) {
    val pairId: ByteArray = Crypto.random(8)
    private val keyPair = EcdhKeyPair.generate()
    private val na = Crypto.random(16)
    private val name = phoneName

    private var pkb: ByteArray? = null
    private var commit: ByteArray? = null
    private var key: ByteArray? = null

    /** The code to show, once the exchange got that far. */
    var code: Int? = null
        private set

    sealed interface Outcome {
        data class Paired(val serverId: ByteArray, val key: ByteArray) : Outcome
        data object WaitingForPc : Outcome
        data class Failed(val status: Protocol.PairStatus?) : Outcome
    }

    fun startPacket(): ByteArray = Protocol.pairStart(pairId, keyPair.public, name)

    fun noncePacket(): ByteArray = Protocol.pairNonce(pairId, na)

    /** Records the PC's key and commitment; the first commit wins. */
    fun onCommit(reply: Protocol.Reply.PairCommit): Boolean {
        if (!reply.pairId.contentEquals(pairId)) return false
        if (pkb == null) {
            pkb = reply.pkb
            commit = reply.commit
        }
        return true
    }

    /**
     * Checks the revealed nonce against the commitment and derives the key.
     * Returns the 6-digit code, or null if the PC's answer is inconsistent
     * (wrong commitment or invalid key): then pairing must be abandoned.
     */
    fun onReveal(reply: Protocol.Reply.PairReveal): Int? {
        code?.let { return it } // retransmitted reveal
        val pkb = pkb ?: return null
        val commit = commit ?: return null
        if (!reply.pairId.contentEquals(pairId)) return null
        if (!MessageDigest.isEqual(Crypto.commitment(pkb, keyPair.public, reply.nb), commit)) return null
        val shared = keyPair.sharedSecret(pkb) ?: return null
        key = Crypto.pairingKey(shared, na, reply.nb, pairId, keyPair.public, pkb)
        shared.fill(0)
        return Crypto.verificationCode(keyPair.public, pkb, na, reply.nb).also { code = it }
    }

    /** Sent after the user confirms that both screens show the same code. */
    fun confirmPacket(): ByteArray = Protocol.pairConfirm(pairId, Crypto.phoneConfirmTag(checkNotNull(key)))

    fun onResult(reply: Protocol.Reply.PairResult): Outcome? {
        if (!reply.pairId.contentEquals(pairId)) return null
        return when (reply.status) {
            Protocol.PairStatus.WAITING -> Outcome.WaitingForPc
            Protocol.PairStatus.ACCEPTED -> {
                val key = key ?: return Outcome.Failed(null)
                // The PC proves it holds the same key and binds its identity.
                if (Crypto.verifyPcConfirm(key, reply.serverId, reply.tag)) {
                    Outcome.Paired(reply.serverId, key)
                } else {
                    Outcome.Failed(null)
                }
            }
            else -> Outcome.Failed(reply.status)
        }
    }
}

/**
 * Starts one session with a paired PC: HELLO proves we hold the pairing key,
 * WELCOME proves the PC does, and both contribute a nonce to a fresh session key.
 */
class SessionHandshake(private val pairId: ByteArray, private val key: ByteArray) {
    private val nc = Crypto.random(16)

    fun helloPacket(): ByteArray = Protocol.hello(pairId, nc, Crypto.helloTag(key, pairId, nc))

    /** A secure channel if this WELCOME is authentic and answers our HELLO. */
    fun onWelcome(w: Protocol.Reply.Welcome): SecureChannel? {
        if (!w.pairId.contentEquals(pairId) || !w.nc.contentEquals(nc)) return null
        if (!Crypto.verifyWelcome(key, pairId, nc, w.ns, w.sessionId, w.tag)) return null
        val sessionKey = Crypto.sessionKey(key, nc, w.ns, pairId, w.sessionId)
        return SecureChannel(sessionKey, w.sessionId, SecureChannel.Role.PHONE).also { sessionKey.fill(0) }
    }
}
