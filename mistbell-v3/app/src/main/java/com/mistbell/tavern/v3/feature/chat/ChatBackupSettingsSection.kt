package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Suppress("FunctionNaming")
@Composable
internal fun ChatBackupSettingsSection(
    onExport: () -> Unit,
    onRestore: () -> Unit,
    statusMessage: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("备份与恢复", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onExport) { Text("导出备份") }
            OutlinedButton(onClick = onRestore) { Text("恢复备份") }
        }
        Text(
            "备份包含聊天记录、会话列表、记忆、世界书、角色卡和设置；不包含模型文件与 API 密钥。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (statusMessage.isNotBlank()) {
            Text(
                statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
