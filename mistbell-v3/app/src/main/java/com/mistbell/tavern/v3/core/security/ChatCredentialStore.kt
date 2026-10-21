package com.mistbell.tavern.v3.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.mistbell.tavern.v3.feature.chat.CredentialResolver
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted storage for chat provider API keys.
 *
 * The preferences file only holds authenticated ciphertext keyed by an opaque handle; the
 * AES-GCM key never leaves the Android Keystore. Profile settings store the handle, so
 * backups and exports never contain the raw key.
 */
class ChatCredentialStore(context: Context) : CredentialResolver {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(apiKey: String): String {
        require(apiKey.isNotBlank() && apiKey.length <= MAX_SECRET_CHARS) {
            "Credential is blank or exceeds the supported size."
        }
        val handle = UUID.randomUUID().toString()
        val payload = Base64.getEncoder().encodeToString(encrypt(apiKey.toByteArray(Charsets.UTF_8)))
        check(preferences.edit().putString(handle, payload).commit()) {
            "Credential could not be stored securely."
        }
        return handle
    }

    @Synchronized
    override fun resolve(handle: String): String? {
        val encoded = handle.takeIf(::isValidHandle)?.let { preferences.getString(it, null) }
        return encoded?.let {
            runCatching {
                decrypt(Base64.getDecoder().decode(it)).toString(Charsets.UTF_8).takeIf(String::isNotBlank)
            }.getOrNull()
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
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plainText)
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "Invalid credential payload." }
        val ivSize = payload[0].toInt() and BYTE_MASK
        require(
            ivSize in MIN_GCM_IV_BYTES..MAX_GCM_IV_BYTES &&
                payload.size >= IV_LENGTH_PREFIX_BYTES + ivSize + GCM_TAG_BYTES,
        ) { "Invalid credential payload." }
        val iv = payload.copyOfRange(IV_LENGTH_PREFIX_BYTES, IV_LENGTH_PREFIX_BYTES + ivSize)
        val encrypted = payload.copyOfRange(IV_LENGTH_PREFIX_BYTES + ivSize, payload.size)
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
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun isValidHandle(handle: String): Boolean =
        handle.length == HANDLE_LENGTH &&
            runCatching { UUID.fromString(handle).toString() == handle }.getOrDefault(false)

    companion object {
        private const val PREFERENCES_NAME = "mistbell-v3-chat-credentials"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mistbell-v3-chat-credential-aes-gcm"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val BYTE_MASK = 0xFF
        private const val IV_LENGTH_PREFIX_BYTES = 1
        private const val MIN_GCM_IV_BYTES = 12
        private const val MAX_GCM_IV_BYTES = 16
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val HANDLE_LENGTH = 36
        private const val KEY_SIZE_BITS = 256
        private const val MAX_SECRET_CHARS = 16_384
    }
}
