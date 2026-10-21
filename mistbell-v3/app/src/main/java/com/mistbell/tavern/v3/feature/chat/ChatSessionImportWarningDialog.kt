package com.mistbell.tavern.v3.feature.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun ChatSessionImportWarningDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入会覆盖当前会话") },
        text = {
            Text("导入的消息会替换当前会话的聊天记录，不会自动创建新会话。建议先导出当前会话备份。是否继续选择文件？")
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("继续选择文件") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("取消") }
        },
    )
}
