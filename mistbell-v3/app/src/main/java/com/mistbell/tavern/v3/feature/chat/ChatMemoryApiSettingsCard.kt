package com.mistbell.tavern.v3.feature.chat

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

@Composable
internal fun ChatMemoryApiSettingsCard(
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    modelId: String,
    onModelIdChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    hasSavedCredential: Boolean,
    onSaveCredential: () -> Unit,
    onDeleteCredential: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("独立记忆整理 API（可选）", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("记忆整理 API Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = modelId,
                onValueChange = onModelIdChange,
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
                Button(onClick = onSaveCredential) { Text("安全保存密钥") }
                Button(
                    onClick = onDeleteCredential,
                    enabled = hasSavedCredential,
                ) { Text("删除已保存密钥") }
            }
            Text(
                "与聊天 API 分开配置。Base URL 和模型 ID 保存在本机；密钥可仅本次使用，或通过 Android Keystore 加密保存。每累计 3 轮已完成对话后异步整理，失败不会撤销聊天回复。",
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
