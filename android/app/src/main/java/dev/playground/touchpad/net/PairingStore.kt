package dev.playground.touchpad.net

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Remembers the paired PC. The pairing key is encrypted with an AES-256-GCM
 * key that lives in the Android Keystore (hardware-backed on most phones) and
 * can't be extracted from it; preferences only ever hold the ciphertext.
 * Backups are disabled in the manifest, so none of this leaves the device.
 */
class PairingStore(private val prefs: SharedPreferences) {

    class Pairing(val serverId: ByteArray, val pcName: String, val pairId: ByteArray, val key: ByteArray)

    fun load(): Pairing? {
        val serverId = prefs.getString(K_SERVER, null)?.let(::fromB64) ?: return null
        val pairId = prefs.getString(K_PAIR, null)?.let(::fromB64) ?: return null
        val iv = prefs.getString(K_IV, null)?.let(::fromB64) ?: return null
        val sealed = prefs.getString(K_KEY, null)?.let(::fromB64) ?: return null
        val name = prefs.getString(K_NAME, null) ?: return null
        val key = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, wrappingKey(create = false) ?: return null, GCMParameterSpec(128, iv))
                updateAAD(pairId + serverId) // binds the key to this pairing record
                doFinal(sealed)
            }
        } catch (_: GeneralSecurityException) {
            // Keystore entry lost (e.g. screen lock removed on some devices) or
            // data tampered with: the only safe option is to pair again.
            clear()
            return null
        }
        return Pairing(serverId, name, pairId, key)
    }

    fun save(p: Pairing) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(create = true)) // Keystore picks a random IV
        cipher.updateAAD(p.pairId + p.serverId)
        val sealed = cipher.doFinal(p.key)
        prefs.edit {
            putString(K_SERVER, b64(p.serverId))
            putString(K_PAIR, b64(p.pairId))
            putString(K_NAME, p.pcName)
            putString(K_IV, b64(cipher.iv))
            putString(K_KEY, b64(sealed))
        }
    }

    /** The paired PC's name (not secret), readable without touching the Keystore. */
    fun pairedPcName(): String? = prefs.getString(K_NAME, null)

    fun clear() {
        prefs.edit {
            listOf(K_SERVER, K_PAIR, K_NAME, K_IV, K_KEY, K_ADDRESS).forEach(::remove)
        }
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    /** Last address the PC answered from; not secret, only speeds up reconnecting. */
    var lastAddress: String?
        get() = prefs.getString(K_ADDRESS, null)
        set(value) = prefs.edit { putString(K_ADDRESS, value) }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun wrappingKey(create: Boolean): SecretKey? {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun fromB64(s: String): ByteArray? = runCatching { Base64.decode(s, Base64.NO_WRAP) }.getOrNull()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "touchpad_pairing_key_wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val K_SERVER = "pc_server_id"
        const val K_PAIR = "pair_id"
        const val K_NAME = "pc_name"
        const val K_IV = "pairing_key_iv"
        const val K_KEY = "pairing_key_sealed"
        const val K_ADDRESS = "last_pc_address"
    }
}
