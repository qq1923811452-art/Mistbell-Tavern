package com.mistbell.tavern.v3.feature.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mistbell.tavern.v3.core.network.ChatConnectionTester
import com.mistbell.tavern.v3.feature.chat.ThinkingModeTemplates
import com.mistbell.tavern.v3.core.security.ChatCredentialStore
import kotlinx.coroutines.launch

/** Edits the single default chat API profile stored in shared preferences. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
fun ApiSettingsSection(onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
    }
    val credentialStore = remember(context) { ChatCredentialStore(context) }
    var profile by remember { mutableStateOf(loadProfile(context)) }
    var showDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ApiProfileCard(
        profile = profile,
        selected = true,
        onTest = {
            val apiKey = profile.handle?.let(credentialStore::resolve).orEmpty()
            if (apiKey.isBlank()) {
                onStatus("没有已保存的密钥，请先编辑并保存配置。")
            } else {
                onStatus("正在测试连接…")
                scope.launch {
                    val result = ChatConnectionTester().test(
                        baseUrl = profile.baseUrl,
                        modelId = profile.modelId,
                        apiKey = apiKey,
                    )
                    onStatus(result.message)
                }
            }
        },
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
                    .putBoolean("chat_long_context", updated.longContext)
                    .putString("chat_profile_name", updated.name)
                    .apply()
                val template = if (updated.thinkingEnabled) {
                    ThinkingModeTemplates.enabled(updated.baseUrl, updated.modelId)
                } else {
                    ThinkingModeTemplates.disabled(updated.baseUrl, updated.modelId)
                }
                prefs.edit()
                    .putString("chat_credential_handle", handle)
                    .apply()
                profile = updated.copy(handle = handle)
                showDialog = false
                onStatus(
                    if (template == null) {
                        "API 配置已保存；该服务商没有内置思考参数，思考开关不会改变请求。"
                    } else {
                        "API 配置已保存。"
                    },
                )
            },
        )
    }
}

private fun loadProfile(context: Context): ApiProfile {
    val prefs = context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
    return ApiProfile(
        id = "default",
        name = prefs.getString("chat_profile_name", "默认配置") ?: "默认配置",
        baseUrl = prefs.getString("chat_api_base_url", "https://api.odys.work/v1") ?: "",
        modelId = prefs.getString("chat_model_id", "") ?: "",
        handle = prefs.getString("chat_credential_handle", null),
        isDefault = true,
        stream = prefs.getBoolean("chat_stream_enabled", true),
        thinkingEnabled = prefs.getBoolean("chat_thinking_enabled", false),
        longContext = prefs.getBoolean("chat_long_context", false),
    )
}
