package com.silenthub.capturelab

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-backed pairing token. Migrates and deletes the 0.4.x plaintext preference. */
internal class PairingTokenStore(context: Context) {
    private val app = context.applicationContext
    private val securePrefs = app.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
    private val legacyPrefs = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreate(): String {
        securePrefs.getString(KEY_CIPHERTEXT, null)?.let { encoded ->
            try {
                return decrypt(encoded)
            } catch (_: Exception) {
                securePrefs.edit().remove(KEY_CIPHERTEXT).apply()
            }
        }

        val legacy = legacyPrefs.getString(LEGACY_TOKEN, null)?.trim().orEmpty()
        if (isValid(legacy)) {
            save(legacy)
            legacyPrefs.edit().remove(LEGACY_TOKEN).apply()
            return legacy
        }

        return newToken().also(::save)
    }

    @Synchronized
    fun rotate(): String {
        securePrefs.edit().remove(KEY_CIPHERTEXT).commit()
        legacyPrefs.edit().remove(LEGACY_TOKEN).apply()
        return newToken().also(::save)
    }

    private fun save(token: String) {
        securePrefs.edit().putString(KEY_CIPHERTEXT, encrypt(token)).commit()
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun isValid(token: String): Boolean =
        token.matches(Regex("^[A-Za-z0-9_-]{43}$"))

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "Unexpected Android Keystore IV length." }
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > GCM_IV_BYTES) { "Encrypted pairing value is truncated." }
        val iv = packed.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = packed.copyOfRange(GCM_IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val token = String(cipher.doFinal(encrypted), Charsets.UTF_8)
        require(isValid(token)) { "Decrypted pairing token is invalid." }
        return token
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val SECURE_PREFS = "silenthub_pairing_secure"
        private const val LEGACY_PREFS = "external_esp_state_v4"
        private const val LEGACY_TOKEN = "pairing_token"
        private const val KEY_CIPHERTEXT = "pairing_token_gcm"
        private const val KEY_ALIAS = "silenthub.external.pairing.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
    }
}