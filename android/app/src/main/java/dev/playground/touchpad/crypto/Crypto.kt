package dev.playground.touchpad.crypto

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The same derivations as the PC's `pc-server/src/crypto.rs`, built only on the
 * platform's `java.security` / `javax.crypto`. Every input is prefixed with a
 * distinct `touchpad/v2/...` label so no key or tag can serve two purposes.
 * Verified against the shared vectors in `docs/test-vectors.txt`.
 */
object Crypto {
    private val rng = SecureRandom()

    private val L_COMMIT = label("commit")
    private val L_CODE = label("code")
    private val L_PAIRING_KEY = label("pairing-key")
    private val L_CONFIRM_PHONE = label("confirm-phone")
    private val L_CONFIRM_PC = label("confirm-pc")
    private val L_HELLO = label("hello")
    private val L_WELCOME = label("welcome")
    private val L_SESSION = label("session")

    private fun label(name: String) = "touchpad/v2/$name".toByteArray(Charsets.US_ASCII)

    fun random(size: Int): ByteArray = ByteArray(size).also(rng::nextBytes)

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    /** HMAC-SHA256 truncated to 128 bits. */
    fun hmacTag(key: ByteArray, vararg parts: ByteArray): ByteArray = hmac(key, *parts).copyOf(16)

    /** Constant-time comparison, so a forged tag can't be guessed byte by byte. */
    fun verifyTag(key: ByteArray, tag: ByteArray, vararg parts: ByteArray): Boolean =
        tag.size == 16 && MessageDigest.isEqual(hmacTag(key, *parts), tag)

    /** HKDF-SHA256 (RFC 5869) producing 32 bytes, i.e. a single expand block. */
    fun hkdf32(salt: ByteArray, ikm: ByteArray, vararg info: ByteArray): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, *info, byteArrayOf(1))
    }

    fun le32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    // ------------------------------------------------------------ pairing

    fun commitment(pkb: ByteArray, pka: ByteArray, nb: ByteArray): ByteArray = sha256(L_COMMIT, pkb, pka, nb)

    fun verificationCode(pka: ByteArray, pkb: ByteArray, na: ByteArray, nb: ByteArray): Int {
        val h = sha256(L_CODE, pka, pkb, na, nb)
        val value = ByteBuffer.wrap(h, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        return (value % 1_000_000L).toInt()
    }

    fun pairingKey(
        shared: ByteArray,
        na: ByteArray,
        nb: ByteArray,
        pairId: ByteArray,
        pka: ByteArray,
        pkb: ByteArray,
    ): ByteArray = hkdf32(na + nb, shared, L_PAIRING_KEY, pairId, pka, pkb)

    fun phoneConfirmTag(key: ByteArray): ByteArray = hmacTag(key, L_CONFIRM_PHONE)

    fun pcConfirmTag(key: ByteArray, serverId: ByteArray): ByteArray = hmacTag(key, L_CONFIRM_PC, serverId)

    fun verifyPcConfirm(key: ByteArray, serverId: ByteArray, tag: ByteArray): Boolean =
        verifyTag(key, tag, L_CONFIRM_PC, serverId)

    // ------------------------------------------------------------ sessions

    fun helloTag(key: ByteArray, pairId: ByteArray, nc: ByteArray): ByteArray = hmacTag(key, L_HELLO, pairId, nc)

    fun welcomeTag(key: ByteArray, pairId: ByteArray, nc: ByteArray, ns: ByteArray, sessionId: Int): ByteArray =
        hmacTag(key, L_WELCOME, pairId, nc, ns, le32(sessionId))

    fun verifyWelcome(key: ByteArray, pairId: ByteArray, nc: ByteArray, ns: ByteArray, sessionId: Int, tag: ByteArray) =
        verifyTag(key, tag, L_WELCOME, pairId, nc, ns, le32(sessionId))

    fun sessionKey(key: ByteArray, nc: ByteArray, ns: ByteArray, pairId: ByteArray, sessionId: Int): ByteArray =
        hkdf32(nc + ns, key, L_SESSION, pairId, le32(sessionId))
}

/** An ephemeral P-256 key pair used for exactly one pairing attempt. */
class EcdhKeyPair private constructor(private val private: PrivateKey, val public: ByteArray) {

    /**
     * ECDH with the peer's uncompressed public key, or null if the bytes are
     * not a point on P-256. Java's KeyFactory does not check this, so it is
     * done here explicitly (protection against invalid-curve attacks).
     */
    fun sharedSecret(peer: ByteArray): ByteArray? {
        val point = decodePoint(peer) ?: return null
        val peerKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, PARAMS))
        return KeyAgreement.getInstance("ECDH").run {
            init(private)
            doPhase(peerKey, true)
            generateSecret()
        }
    }

    companion object {
        const val POINT_LEN = 65
        private val PARAMS: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }

        fun generate(): EcdhKeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val pair = generator.generateKeyPair()
            val w = (pair.public as ECPublicKey).w
            return EcdhKeyPair(pair.private, byteArrayOf(4) + fixed32(w.affineX) + fixed32(w.affineY))
        }

        private fun fixed32(n: BigInteger): ByteArray {
            val raw = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            return ByteArray(32 - raw.size) + raw
        }

        /** Parses an uncompressed SEC1 point and checks y² = x³ + ax + b (mod p). */
        fun decodePoint(bytes: ByteArray): ECPoint? {
            if (bytes.size != POINT_LEN || bytes[0] != 4.toByte()) return null
            val x = BigInteger(1, bytes.copyOfRange(1, 33))
            val y = BigInteger(1, bytes.copyOfRange(33, 65))
            val curve = PARAMS.curve
            val p = (curve.field as ECFieldFp).p
            if (x >= p || y >= p) return null
            val lhs = y.multiply(y).mod(p)
            val rhs = x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(p)
            return if (lhs == rhs) ECPoint(x, y) else null
        }
    }
}
