@file:Suppress("FunctionNaming")

package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

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
        placeholder = { Text("输入消息…") },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        minLines = 1,
        maxLines = 4,
        shape = RoundedCornerShape(24.dp),
        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
            textAlign = TextAlign.Start,
        ),
    )
}
