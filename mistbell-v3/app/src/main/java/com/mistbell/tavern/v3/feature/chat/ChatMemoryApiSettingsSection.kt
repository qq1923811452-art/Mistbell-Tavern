package com.mistbell.tavern.v3.feature.chat

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore

@Composable
internal fun ChatMemoryApiSettingsSection(
    settings: SharedPreferences,
    baseUrl: String,
    modelId: String,
    apiKey: String,
    hasSavedCredential: Boolean,
    credentialStore: AndroidKeystoreMemoryCredentialStore,
    diagnosticStore: DiagnosticLogStore,
    onBaseUrlChange: (String) -> Unit,
    onModelIdChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSavedCredentialChange: (Boolean) -> Unit,
    onStatusChange: (String) -> Unit,
) {
    val credentialCoordinator = ChatMemoryCredentialCoordinator(
        settings = settings,
        credentialStore = credentialStore,
        diagnosticStore = diagnosticStore,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("独立记忆整理 API（可选）", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    settings.edit().putString("memory_api_base_url", it).apply()
                    onBaseUrlChange(it)
                },
                label = { Text("记忆整理 API Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = modelId,
                onValueChange = {
                    settings.edit().putString("memory_model_id", it).apply()
                    onModelIdChange(it)
                },
                label = { Text("记忆整理模型 ID（留空则关闭）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("独立记忆 API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val result = credentialCoordinator.save(apiKey)
                    onStatusChange(result.statusMessage)
                    if (result.success) {
                        onApiKeyChange("")
                        onSavedCredentialChange(true)
                    }
                }) {
                    Text("安全保存密钥")
                }
                Button(
                    onClick = {
                        val result = credentialCoordinator.deleteSaved()
                        onStatusChange(result.statusMessage)
                        if (result.success) onSavedCredentialChange(false)
                    },
                    enabled = hasSavedCredential,
                ) {
                    Text("删除已保存密钥")
                }
            }
            Text(
                "与聊天 API 分开配置。Base URL 和模型 ID 保存在本机；密钥不会写入 SharedPreferences 或诊断日志。每累计 3 轮已完成对话后异步整理，失败不会撤销聊天回复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (hasSavedCredential) "已保存加密密钥；不会显示或回填明文。" else "尚未保存记忆 API 密钥。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
