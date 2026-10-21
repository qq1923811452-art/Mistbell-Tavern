package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
internal fun ChatApiEndpointFields(
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    modelId: String,
    onModelIdChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
) {
    OutlinedTextField(value = baseUrl, onValueChange = onBaseUrlChange, label = { Text("API Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(value = modelId, onValueChange = onModelIdChange, label = { Text("模型 ID（必填）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(value = apiKey, onValueChange = onApiKeyChange, label = { Text("API Key（远程接口必填）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
    Text("聊天 API Key 仅保存在当前页面内存中，离开页面后需要重新输入；独立记忆 API Key 可选择加密保存到 Android Keystore。Base URL 和模型 ID 会保存在本机设置中。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
