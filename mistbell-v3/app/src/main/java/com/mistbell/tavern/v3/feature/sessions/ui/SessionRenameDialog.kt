package com.mistbell.tavern.v3.feature.sessions.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mistbell.tavern.v3.feature.sessions.SessionStore

@Composable
fun SessionRenameDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle.take(SessionStore.MAX_TITLE_CHARS)) }
    val normalizedTitle = title.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名对话") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(SessionStore.MAX_TITLE_CHARS) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("对话标题") },
                singleLine = true,
                supportingText = { Text("${title.length}/${SessionStore.MAX_TITLE_CHARS}") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalizedTitle) },
                enabled = normalizedTitle.isNotEmpty(),
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
