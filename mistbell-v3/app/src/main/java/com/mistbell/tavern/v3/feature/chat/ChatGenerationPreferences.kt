package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences

/**
 * Durable chat generation switches. Values live next to the other 3.0 settings so backups can
 * export them as plain preferences without touching encrypted credentials.
 */
class ChatGenerationPreferences(private val settings: SharedPreferences) {

    val streamEnabled: Boolean
        get() = settings.getBoolean(KEY_STREAM_ENABLED, true)

    val thinkingEnabled: Boolean
        get() = settings.getBoolean(KEY_THINKING_ENABLED, false)

    val credentialHandle: String?
        get() = settings.getString(KEY_CREDENTIAL_HANDLE, null)?.takeIf(String::isNotBlank)

    fun setStreamEnabled(enabled: Boolean) {
        settings.edit().putBoolean(KEY_STREAM_ENABLED, enabled).apply()
    }

    fun setThinkingEnabled(enabled: Boolean) {
        settings.edit().putBoolean(KEY_THINKING_ENABLED, enabled).apply()
    }

    fun setCredentialHandle(handle: String?) {
        settings.edit().apply {
            if (handle.isNullOrBlank()) remove(KEY_CREDENTIAL_HANDLE) else putString(KEY_CREDENTIAL_HANDLE, handle)
        }.apply()
    }

    companion object {
        const val KEY_STREAM_ENABLED = "chat_stream_enabled"
        const val KEY_THINKING_ENABLED = "chat_thinking_enabled"
        const val KEY_CREDENTIAL_HANDLE = "chat_credential_handle"
    }
}
