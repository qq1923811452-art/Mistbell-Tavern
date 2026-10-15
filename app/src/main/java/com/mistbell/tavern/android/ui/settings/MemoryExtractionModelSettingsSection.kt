package com.mistbell.tavern.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.data.repository.MemoryExtractionModelSettings
import com.mistbell.tavern.android.data.repository.MemoryExtractionModelSettingsRepository
import com.mistbell.tavern.android.ui.common.ModernSettingsGroup
import kotlinx.coroutines.launch
import java.net.URI

@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
@Composable
fun MemoryExtractionModelSettingsSection() {
    val repository = remember { MemoryExtractionModelSettingsRepository() }
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var keyNeedsReentry by remember { mutableStateOf(false) }
    var hasStoredKey by remember { mutableStateOf(false) }
    var clearKeyRequested by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val settings = repository.load()
            baseUrl = settings.baseUrl
            apiKey = settings.apiKey
            model = settings.model
            keyNeedsReentry = settings.keyNeedsReentry
            hasStoredKey = settings.apiKey.isNotBlank() || settings.keyNeedsReentry
            clearKeyRequested = false
            if (keyNeedsReentry) {
                status = "已保存的 API Key 无法解密，请重新输入并保存；保存前会回退到聊天 API"
            }
        } catch (_: Exception) {
            status = "读取独立记忆模型配置失败"
        } finally {
            loading = false
        }
    }

    ModernSettingsGroup(title = "🧠 独立记忆整理模型") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text =
                    "可单独配置 Qwen 等模型整理长期记忆，不占用聊天模型的上下文。" +
                        "远程 API 需填写 Key；只有本机回环地址允许留空。" +
                        "有效配置优先用于后台记忆抽取；否则沿用当前会话的 API 配置，不改变聊天提示词前缀或缓存布局。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; status = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("记忆整理 API 基础地址") },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true,
                enabled = !loading && !saving,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    keyNeedsReentry = false
                    clearKeyRequested = false
                    if (it.isNotBlank()) hasStoredKey = true
                    status = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("独立记忆整理 API Key（本机回环地址可留空）") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                enabled = !loading && !saving,
            )
            if (hasStoredKey || apiKey.isNotBlank() || keyNeedsReentry) {
                TextButton(
                    onClick = {
                        apiKey = ""
                        keyNeedsReentry = false
                        clearKeyRequested = true
                        status = "保存时将清除已保存的 API Key"
                    },
                    enabled = !loading && !saving,
                ) {
                    Text("清除已保存 Key")
                }
            }
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; status = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("记忆整理模型 ID") },
                placeholder = { Text("由服务商提供，例如 Qwen 模型 ID") },
                singleLine = true,
                enabled = !loading && !saving,
            )
            Button(
                onClick = {
                    saving = true
                    status = null
                    scope.launch {
                        try {
                            repository.save(
                                MemoryExtractionModelSettings(
                                    baseUrl = baseUrl,
                                    apiKey = apiKey,
                                    model = model,
                                    keyNeedsReentry = keyNeedsReentry,
                                    clearApiKey = clearKeyRequested,
                                ),
                            )
                            val savedSettings = repository.load()
                            apiKey = savedSettings.apiKey
                            keyNeedsReentry = savedSettings.keyNeedsReentry
                            hasStoredKey = savedSettings.apiKey.isNotBlank() || savedSettings.keyNeedsReentry
                            clearKeyRequested = false
                            val configured = savedSettings.toLlmConfigOrNull() != null
                            val endpointError =
                                MemoryExtractionModelSettings(baseUrl, apiKey, model).endpointRejectionReason()
                            status = if (configured) {
                                "独立记忆模型配置已保存；后续后台抽取将优先使用它"
                            } else if (endpointError != null) {
                                "配置已保存，但 $endpointError；当前将回退到聊天 API"
                            } else if (isRemoteApiMissingKey(baseUrl, apiKey, model)) {
                                "配置已保存；远程 API 还需要 Key，当前将回退到聊天 API"
                            } else {
                                "配置已保存；基础地址和模型 ID 有效后才会启用独立模型"
                            }
                        } catch (_: Exception) {
                            status = "保存失败，请检查输入后重试"
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !loading && !saving,
            ) {
                Text(if (saving) "正在保存…" else "保存记忆模型配置")
            }
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun isRemoteApiMissingKey(baseUrl: String, apiKey: String, model: String): Boolean {
    val host = runCatching { URI(baseUrl.trim()).host?.lowercase() }.getOrNull()
    val hasEndpointAndModel = baseUrl.isNotBlank() && model.isNotBlank()
    val missingKey = apiKey.isBlank()
    val isLoopback = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
    return hasEndpointAndModel && missingKey && !isLoopback
}