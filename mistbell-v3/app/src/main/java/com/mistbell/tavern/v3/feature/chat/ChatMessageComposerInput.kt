package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun ChatMessageComposerInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("输入消息") },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        minLines = 2,
        maxLines = 4,
    )
}
