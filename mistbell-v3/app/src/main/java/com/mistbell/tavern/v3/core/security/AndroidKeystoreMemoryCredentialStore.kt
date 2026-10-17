package com.mistbell.tavern.v3.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.mistbell.tavern.v3.feature.memory.MemoryCredentialResolver
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores provider secrets encrypted with an Android Keystore AES key.
 *
 * Only opaque handles belong in provider profiles and durable background jobs. The preferences
 * file contains authenticated ciphertext, never the raw API key. Errors intentionally omit
 * provider data and cryptographic exception text.
 */
class AndroidKeystoreMemoryCredentialStore(context: Context) : MemoryCredentialResolver {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun put(secret: String): String {
        require(secret.isNotBlank() && secret.length <= MAX_SECRET_CHARS) {
            "Credential is blank or exceeds the supported size."
        }
        val handle = UUID.randomUUID().toString()
        val encrypted = encrypt(secret.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(handle, Base64.getEncoder().encodeToString(encrypted)).commit()) {
            "Credential could not be stored securely."
        }
        return handle
    }

    @Synchronized
    override fun resolve(handle: String): String? {
        if (!isValidHandle(handle)) return null
        val encoded = preferences.getString(handle, null) ?: return null
        return try {
            val bytes = Base64.getDecoder().decode(encoded)
            decrypt(bytes).toString(Charsets.UTF_8).takeIf(String::isNotBlank)
        } catch (_: Exception) {
            // Do not leak secrets or low-level keystore details into logs or queue errors.
            null
        }
    }

    @Synchronized
    fun delete(handle: String): Boolean {
        if (!isValidHandle(handle) || !preferences.contains(handle)) return false
        return preferences.edit().remove(handle).commit()
    }

    @Synchronized
    fun contains(handle: String): Boolean = isValidHandle(handle) && preferences.contains(handle)

    private fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText)
        return byteArrayOf(iv.size.toByte()) + iv + encrypted
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "Invalid credential payload." }
        val ivSize = payload[0].toInt() and 0xff
        // A valid AES-GCM payload may contain only the IV and authentication tag
        // when encrypting an empty plaintext, so the minimum length is inclusive.
        require(ivSize in MIN_GCM_IV_BYTES..MAX_GCM_IV_BYTES && payload.size >= 1 + ivSize + GCM_TAG_BYTES) {
            "Invalid credential payload."
        }
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val encrypted = payload.copyOfRange(1 + ivSize, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun isValidHandle(handle: String): Boolean =
        handle.length == HANDLE_LENGTH && runCatching { UUID.fromString(handle).toString() == handle }.getOrDefault(false)

    companion object {
        private const val PREFERENCES_NAME = "mistbell-v3-encrypted-provider-credentials"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mistbell-v3-memory-credential-aes-gcm"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MIN_GCM_IV_BYTES = 12
        private const val MAX_GCM_IV_BYTES = 16
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val HANDLE_LENGTH = 36
        private const val MAX_SECRET_CHARS = 16_384
    }
}
