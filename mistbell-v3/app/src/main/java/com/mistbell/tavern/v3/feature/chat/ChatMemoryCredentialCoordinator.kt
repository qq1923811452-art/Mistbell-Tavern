package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore

internal data class ChatMemoryCredentialOperationResult(
    val success: Boolean,
    val statusMessage: String,
)

/**
 * Owns persistence of the optional memory API credential while keeping plaintext
 * credentials out of SharedPreferences and diagnostics.
 */
internal class ChatMemoryCredentialCoordinator(
    private val settings: SharedPreferences,
    private val credentialStore: AndroidKeystoreMemoryCredentialStore,
    private val diagnosticStore: DiagnosticLogStore,
) {
    fun save(apiKey: String): ChatMemoryCredentialOperationResult {
        if (apiKey.isBlank()) {
            return ChatMemoryCredentialOperationResult(
                success = false,
                statusMessage = "请先输入记忆整理 API Key，再保存到 Android Keystore。",
            )
        }

        return runCatching {
            val newHandle = credentialStore.put(apiKey)
            val oldHandle = settings.getString(CREDENTIAL_HANDLE_KEY, null)
            if (!settings.edit().putString(CREDENTIAL_HANDLE_KEY, newHandle).commit()) {
                credentialStore.delete(newHandle)
                error("Credential handle could not be saved.")
            }
            if (!oldHandle.isNullOrBlank() && oldHandle != newHandle) {
                credentialStore.delete(oldHandle)
            }
            diagnosticStore.info("memory.credential.saved")
            ChatMemoryCredentialOperationResult(
                success = true,
                statusMessage = "记忆整理 API Key 已加密保存；设置中只保存凭据句柄。",
            )
        }.getOrElse { error ->
            diagnosticStore.warn(
                "memory.credential.save_failed",
                "reason=${error::class.java.simpleName}",
            )
            ChatMemoryCredentialOperationResult(
                success = false,
                statusMessage = "记忆 API Key 保存失败：${error::class.java.simpleName}",
            )
        }
    }

    fun deleteSaved(): ChatMemoryCredentialOperationResult {
        val handle = settings.getString(CREDENTIAL_HANDLE_KEY, null)
        if (!handle.isNullOrBlank()) credentialStore.delete(handle)
        settings.edit().remove(CREDENTIAL_HANDLE_KEY).commit()
        diagnosticStore.info("memory.credential.deleted")
        return ChatMemoryCredentialOperationResult(
            success = true,
            statusMessage = "已删除保存的记忆 API 密钥。",
        )
    }

    private companion object {
        const val CREDENTIAL_HANDLE_KEY = "memory_api_credential_handle"
    }
}
