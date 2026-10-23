@file:Suppress("FunctionNaming")

package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun ChatMessageComposerInput(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        label = { Text("输入消息") },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        minLines = 1,
        maxLines = 4,
    )
}
