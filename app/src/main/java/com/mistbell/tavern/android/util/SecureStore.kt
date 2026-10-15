package com.mistbell.tavern.android.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 AndroidKeyStore 的轻量字符串加解密，用于 API Key 等敏感数据落盘前的包装。
 *
 * 存储格式："enc:v1:" + Base64(iv(12B) + ciphertext)。
 *
 * 迁移语义（重要）：
 * - [unwrap] 遇到不带前缀的历史明文值时原样返回，不抛异常——旧数据永远可读；
 * - [wrap] 每次写入都加密，调用方无需单独的明文迁移步骤，数据在下次保存时自然完成加密；
 * - 空串原样返回（无敏感性的空值不值得一次 Keystore 往返）。
 */
object SecureStore {
    private const val TAG = "SecureStore"
    private const val KEY_ALIAS = "mistbell_secure_v1"
    private const val PREFIX = "enc:v1:"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    @Volatile
    private var cachedKey: SecretKey? = null

    fun hasCipherPrefix(value: String): Boolean = value.startsWith(PREFIX)

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    /** 写入敏感字段前调用；失败时退回明文存储（宁可弱存储也不丢数据）。 */
    fun wrap(plain: String): String {
        if (plain.isEmpty() || isEncrypted(plain)) return plain
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.getEncoder().encodeToString(iv + ciphertext)
        } catch (e: Exception) {
            Log.w(TAG, "encrypt failed, falling back to plaintext", e)
            plain
        }
    }

    /** 读取敏感字段前调用；历史明文原样返回；密文解密失败返回空串（值已不可恢复，空串能让 UI 呈现"未配置"而非坏密钥）。 */
    fun unwrap(value: String): String {
        if (value.isEmpty() || !isEncrypted(value)) return value
        return try {
            val all = Base64.getDecoder().decode(value.removePrefix(PREFIX))
            require(all.size > IV_SIZE) { "ciphertext too short" }
            val iv = all.copyOfRange(0, IV_SIZE)
            val ciphertext = all.copyOfRange(IV_SIZE, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "decrypt failed, treating as unset", e)
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
                cachedKey = it
                return it
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey().also { cachedKey = it }
        }
    }
}
