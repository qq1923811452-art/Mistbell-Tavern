package com.mistbell.tavern.v3.feature.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mistbell.tavern.v3.core.security.ChatCredentialStore

/** Edits the single default chat API profile stored in shared preferences. */
@Suppress("FunctionNaming")
@Composable
fun ApiSettingsSection(onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
    }
    val credentialStore = remember(context) { ChatCredentialStore(context) }
    var profile by remember { mutableStateOf(loadProfile(context)) }
    var showDialog by remember { mutableStateOf(false) }

    ApiProfileCard(
        profile = profile,
        selected = true,
        onTest = { onStatus("配置已保存；发送一条消息即可验证连接。") },
        onClick = { showDialog = true },
        onSetDefault = {},
    )
    if (showDialog) {
        ApiConfigDialog(
            initial = profile,
            onDismiss = { showDialog = false },
            onConfirm = { updated, apiKey ->
                var handle = updated.handle
                if (apiKey.isNotBlank()) {
                    handle = runCatching { credentialStore.save(apiKey) }.getOrNull()
                }
                prefs.edit()
                    .putString("chat_api_base_url", updated.baseUrl.trim())
                    .putString("chat_model_id", updated.modelId.trim())
                    .putBoolean("chat_stream_enabled", updated.stream)
                    .putBoolean("chat_thinking_enabled", updated.thinkingEnabled)
                    .apply()
                prefs.edit()
                    .putString("chat_credential_handle", handle)
                    .apply()
                profile = updated.copy(handle = handle)
                showDialog = false
                onStatus("API 配置已保存。")
            },
        )
    }
}

private fun loadProfile(context: Context): ApiProfile {
    val prefs = context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
    return ApiProfile(
        id = "default",
        name = "默认配置",
        baseUrl = prefs.getString("chat_api_base_url", "https://api.deepseek.com") ?: "",
        modelId = prefs.getString("chat_model_id", "") ?: "",
        handle = prefs.getString("chat_credential_handle", null),
        isDefault = true,
        stream = prefs.getBoolean("chat_stream_enabled", true),
        thinkingEnabled = prefs.getBoolean("chat_thinking_enabled", false),
    )
}
