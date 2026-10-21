package com.mistbell.tavern.v3.feature.chat

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun ChatMessageSendButton(
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
    ) {
        Text(label)
    }
}
