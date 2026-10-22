package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun ChatGenerationSwitchSection(
    hasSavedCredential: Boolean,
    canSaveCredential: Boolean,
    streamEnabled: Boolean,
    thinkingEnabled: Boolean,
    statusMessage: String,
    onSaveCredential: () -> Unit,
    onDeleteCredential: () -> Unit,
    onStreamChange: (Boolean) -> Unit,
    onThinkingChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("生成设置", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSaveCredential, enabled = canSaveCredential) { Text("保存密钥") }
            OutlinedButton(onClick = onDeleteCredential, enabled = hasSavedCredential) {
                Text("删除已保存密钥")
            }
        }
        Text(
            if (hasSavedCredential) "已加密保存聊天 API Key；重启后自动使用。" else "尚未保存聊天 API Key。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("流式输出")
            Switch(checked = streamEnabled, onCheckedChange = onStreamChange)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("思考模式")
            Switch(checked = thinkingEnabled, onCheckedChange = onThinkingChange)
        }
        Text(
            "关闭流式后等待完整回复，停止按钮不再生效；思考模式仅在模型支持时产生效果。",
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
